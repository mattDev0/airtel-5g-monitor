import Foundation

@MainActor
public final class SpeedTestRunner: ObservableObject {
    public nonisolated static let defaultBaseUrl = "https://speed.cloudflare.com"

    @Published public private(set) var progress = SpeedTestProgress()

    private var activeTask: Task<Void, Never>?
    private let baseUrl: String
    private let pingSamples: Int
    private let downloadBytes: Int64
    private let uploadBytes: Int

    public init(
        baseUrl: String = SpeedTestRunner.defaultBaseUrl,
        pingSamples: Int = 5,
        downloadBytes: Int64 = 10_000_000,
        uploadBytes: Int = 5_000_000
    ) {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.pingSamples = max(1, pingSamples)
        self.downloadBytes = downloadBytes
        self.uploadBytes = uploadBytes
    }

    public func start() {
        cancel()
        progress = SpeedTestProgress(phase: .measuringLatency, progressFraction: 0.02)
        activeTask = Task { [weak self] in
            await self?.executeRun()
        }
    }

    public func cancel() {
        activeTask?.cancel()
        activeTask = nil
        if progress.isRunning {
            progress = SpeedTestProgress(
                phase: .cancelled,
                latencyMs: progress.latencyMs,
                jitterMs: progress.jitterMs,
                downloadMbps: progress.downloadMbps,
                uploadMbps: progress.uploadMbps,
                peakDownloadMbps: progress.peakDownloadMbps,
                progressFraction: 0
            )
        }
    }

    public func reset() {
        cancel()
        progress = SpeedTestProgress()
    }

    private func executeRun() async {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 60
        let urlSession = URLSession(configuration: cfg)
        defer { urlSession.invalidateAndCancel() }

        do {
            var rtts: [Double] = []
            for i in 0..<pingSamples {
                try Task.checkCancellation()
                let nonce = Int64(Date().timeIntervalSince1970 * 1_000_000)
                guard let url = URL(string: "\(baseUrl)/__down?bytes=0&t=\(nonce)") else {
                    throw NSError(domain: "SpeedTest", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid speed test URL"])
                }
                var req = URLRequest(url: url)
                req.cachePolicy = .reloadIgnoringLocalCacheData
                req.setValue("no-store", forHTTPHeaderField: "Cache-Control")

                let t0 = DispatchTime.now().uptimeNanoseconds
                let (_, resp) = try await urlSession.data(for: req)
                let t1 = DispatchTime.now().uptimeNanoseconds
                if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                    throw NSError(domain: "SpeedTest", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Latency probe returned HTTP \(http.statusCode)"])
                }
                rtts.append(Double(t1 - t0) / 1_000_000.0)
                let avgPing = rtts.reduce(0, +) / Double(rtts.count)
                let jitter = Self.computeJitter(rtts)
                progress = SpeedTestProgress(
                    phase: .measuringLatency,
                    latencyMs: avgPing,
                    jitterMs: jitter,
                    progressFraction: Double(i + 1) / Double(pingSamples) * 0.2
                )
            }

            let avgPing = rtts.reduce(0, +) / Double(rtts.count)
            let jitter = Self.computeJitter(rtts)

            try Task.checkCancellation()
            let (dlMbps, peakDl) = try await measureDownload(
                urlSession: urlSession,
                avgPing: avgPing,
                jitter: jitter
            )

            try Task.checkCancellation()
            let ulMbps = try await measureUpload(
                urlSession: urlSession,
                avgPing: avgPing,
                jitter: jitter,
                dlMbps: dlMbps,
                peakDl: peakDl
            )

            progress = SpeedTestProgress(
                phase: .finished,
                latencyMs: avgPing,
                jitterMs: jitter,
                downloadMbps: dlMbps,
                uploadMbps: ulMbps,
                peakDownloadMbps: max(peakDl, dlMbps),
                progressFraction: 1.0
            )
        } catch is CancellationError {
            if progress.phase != .cancelled {
                progress = SpeedTestProgress(phase: .cancelled)
            }
        } catch {
            progress = SpeedTestProgress(
                phase: .failed,
                latencyMs: progress.latencyMs,
                jitterMs: progress.jitterMs,
                downloadMbps: progress.downloadMbps,
                uploadMbps: progress.uploadMbps,
                peakDownloadMbps: progress.peakDownloadMbps,
                progressFraction: 0,
                errorMessage: error.localizedDescription
            )
        }
    }

    private func measureDownload(
        urlSession: URLSession,
        avgPing: Double,
        jitter: Double
    ) async throws -> (Double, Double) {
        let nonce = Int64(Date().timeIntervalSince1970 * 1_000_000)
        guard let url = URL(string: "\(baseUrl)/__down?bytes=\(downloadBytes)&t=\(nonce)") else {
            throw NSError(domain: "SpeedTest", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid download URL"])
        }
        var req = URLRequest(url: url)
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.setValue("no-store", forHTTPHeaderField: "Cache-Control")

        let (asyncBytes, resp) = try await urlSession.bytes(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw NSError(domain: "SpeedTest", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Download failed with HTTP \(http.statusCode)"])
        }

        let startNs = DispatchTime.now().uptimeNanoseconds
        var lastEmitNs = startNs
        var totalBytes: Int64 = 0
        var peakMbps: Double = 0

        for try await _ in asyncBytes {
            try Task.checkCancellation()
            totalBytes += 1
            if totalBytes % 16_384 == 0 {
                let nowNs = DispatchTime.now().uptimeNanoseconds
                if nowNs - lastEmitNs >= 120_000_000 {
                    let elapsedSec = max(0.001, Double(nowNs - startNs) / 1_000_000_000.0)
                    let mbps = (Double(totalBytes) * 8.0) / (elapsedSec * 1_000_000.0)
                    peakMbps = max(peakMbps, mbps)
                    let frac = 0.2 + min(1.0, Double(totalBytes) / Double(max(1, downloadBytes))) * 0.4
                    progress = SpeedTestProgress(
                        phase: .downloading,
                        latencyMs: avgPing,
                        jitterMs: jitter,
                        downloadMbps: mbps,
                        peakDownloadMbps: peakMbps,
                        progressFraction: frac
                    )
                    lastEmitNs = nowNs
                }
            }
        }

        let totalSec = max(0.001, Double(DispatchTime.now().uptimeNanoseconds - startNs) / 1_000_000_000.0)
        let finalDl = (Double(totalBytes) * 8.0) / (totalSec * 1_000_000.0)
        peakMbps = max(peakMbps, finalDl)
        progress = SpeedTestProgress(
            phase: .downloading,
            latencyMs: avgPing,
            jitterMs: jitter,
            downloadMbps: finalDl,
            peakDownloadMbps: peakMbps,
            progressFraction: 0.6
        )
        return (finalDl, peakMbps)
    }

    private func measureUpload(
        urlSession: URLSession,
        avgPing: Double,
        jitter: Double,
        dlMbps: Double,
        peakDl: Double
    ) async throws -> Double {
        guard let url = URL(string: "\(baseUrl)/__up") else {
            throw NSError(domain: "SpeedTest", code: 3, userInfo: [NSLocalizedDescriptionKey: "Invalid upload URL"])
        }
        let payloadSize = max(250_000, uploadBytes / 4)
        let payload = Data(repeating: 0x5A, count: payloadSize)

        var totalSent: Int64 = 0
        let chunks = 4
        let startNs = DispatchTime.now().uptimeNanoseconds

        for idx in 0..<chunks {
            try Task.checkCancellation()
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            req.setValue("no-store", forHTTPHeaderField: "Cache-Control")

            let (_, resp) = try await urlSession.upload(for: req, from: payload)
            if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw NSError(domain: "SpeedTest", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Upload failed with HTTP \(http.statusCode)"])
            }
            totalSent += Int64(payloadSize)
            let elapsedSec = max(0.001, Double(DispatchTime.now().uptimeNanoseconds - startNs) / 1_000_000_000.0)
            let mbps = (Double(totalSent) * 8.0) / (elapsedSec * 1_000_000.0)
            progress = SpeedTestProgress(
                phase: .uploading,
                latencyMs: avgPing,
                jitterMs: jitter,
                downloadMbps: dlMbps,
                uploadMbps: mbps,
                peakDownloadMbps: peakDl,
                progressFraction: 0.6 + Double(idx + 1) / Double(chunks) * 0.4
            )
        }

        let totalSec = max(0.001, Double(DispatchTime.now().uptimeNanoseconds - startNs) / 1_000_000_000.0)
        return (Double(totalSent) * 8.0) / (totalSec * 1_000_000.0)
    }

    public static func computeJitter(_ rtts: [Double]) -> Double {
        guard rtts.count > 1 else { return 0 }
        var total = 0.0
        for i in 1..<rtts.count {
            total += abs(rtts[i] - rtts[i - 1])
        }
        return total / Double(rtts.count - 1)
    }
}
