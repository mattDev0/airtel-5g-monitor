import Foundation
import AVFoundation
import UIKit

/// Synthesizes short audio sine-wave pips + subtle haptic feedback scaled with SINR during antenna alignment.
@MainActor
public final class AlignmentTonePlayer: ObservableObject {
    @Published public private(set) var isMuted: Bool = false

    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var lastBeepDate: Date = .distantPast
    private let sampleRate: Double = 44_100

    public init() {}

    public func setMuted(_ muted: Bool) {
        isMuted = muted
    }

    public func playPip(sinrDb: Double?) {
        guard !isMuted, let sinr = sinrDb else { return }
        let now = Date()
        guard now.timeIntervalSince(lastBeepDate) >= 0.7 else { return }
        lastBeepDate = now

        let normalized = min(1.0, max(0.0, (sinr + 5.0) / 35.0))
        let freqHz = 520.0 + normalized * 760.0

        ensureEngineRunning()
        guard let node = playerNode, let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1) else {
            return
        }

        let durationSec = 0.07
        let frameCount = AVAudioFrameCount(sampleRate * durationSec)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else { return }
        buffer.frameLength = frameCount

        if let channel = buffer.floatChannelData?[0] {
            let twoPi = 2.0 * Double.pi
            for frame in 0..<Int(frameCount) {
                let t = Double(frame) / sampleRate
                let env = min(1.0, min(t / 0.01, (durationSec - t) / 0.015))
                channel[frame] = Float(sin(twoPi * freqHz * t) * env * 0.25)
            }
        }

        node.scheduleBuffer(buffer, completionHandler: nil)
        if !node.isPlaying {
            node.play()
        }

        if normalized >= 0.55 {
            let haptic = UIImpactFeedbackGenerator(style: normalized >= 0.8 ? .medium : .light)
            haptic.impactOccurred()
        }
    }

    public func stop() {
        playerNode?.stop()
        engine?.stop()
        engine = nil
        playerNode = nil
    }

    private func ensureEngineRunning() {
        if let engine, engine.isRunning { return }
        let audioEngine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        audioEngine.attach(node)
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1) else { return }
        audioEngine.connect(node, to: audioEngine.mainMixerNode, format: format)
        do {
            try AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            try audioEngine.start()
            self.engine = audioEngine
            self.playerNode = node
        } catch {
            // Audio unavailable (e.g., simulator/CI without output device); silently ignore.
        }
    }
}
