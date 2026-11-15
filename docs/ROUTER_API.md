# ZLT X17M router API map (read-only sweep, 2026-11-15)

Firmware on this unit: Airtel NG (aeraId NG0002). Endpoint: `POST http://<host>/cgi-bin/http.cgi`, JSON body `{cmd, method, sessionId, ...}`.
Login: cmd 232 → `token`; cmd 100 POST with `passwd = sha256(token + password)`. Writes need a fresh `token` from cmd 233 in the payload (see existing `ZltRouterClient`).
Command names come from `js/main.js` on the router. Values are not recorded here on purpose (secrets, SIM ids).

## Readable with a plain GET

| cmd | name | fields |
|---|---|---|
| 2 | WIRELESS_CONFIG | wifiMode, wifiMasterSwitch, wifiSames, wifiOpen, broadcast, wifiwmm, cipherSuite, authenticationType, ssid, key, ssid5G, is_smartwifi_active_flag, original_ssid, wifimac, cur_bandwidth, ssidList, wifiOpen_5g, wifiOpen_6g, mesh_switch, wifi24Channel |
| 11 | SET_DATETIME | ntpSwitch, timezone, timeServer, timeServer2, timeServer3, timeServer4, systime |
| 12 | SMS_INFO | maxSize, receive_full, send_full, draft_full |
| 16 | SMS_SETTING | dmCsca, send_full, smsSw, smscSw, maxSize, maxLen, redirect_sw, redirect_phone |
| 21 | PORT_FILTER | (list/empty — needs page params) |
| 22 | IP_FILTER | (list/empty — needs page params) |
| 23 | MAC_FILTER | (list/empty — needs page params) |
| 24 | IP_MAC_BINDING | (list/empty — needs page params) |
| 26 | URL_FILTER | (list/empty — needs page params) |
| 33 | FIRWALL_UPNP | upnpStatus |
| 43 | DEVICE_VERSION_INFO | real_fwversion, board_type, hwversion, build_date, config_version |
| 80 | INIT_PAGE | isLogoExists, deviceType, displayedVersion, logoPath, aeraId, languageList, channelOneToFour, hideChannel12And13, showAutoUpgrade, isAutoUpgrade, is_show_wf2_infomation, is_show_secondary_wifi, is_show_qr_code, is_show_langchange, is_show_step, copyright, showTerminal, jiangxi_config, wifiMac, urlFilter_display, showRelay, w900_channel_24g, w900_channel_5g, w900_ipoe, w900_wps, w900_status_item, w900_wep, led_show, w900_model, smsSw, isFirstLogin, showExpireTimeUnit, sms_lock_sw, lock_operator_list, antenna_status_display, first_login_flag, need_change_root_pwd, need_change_user_pwd, first_login_step, need_set_pin, dialMode, multiApnNum, ip_passthrough_sw, isOutdoor, dfsSwitch, countryCode, roamingEnable, enable_rsa_encrypt_change_passwd, user_login_times_limit, allowCopyPaste, wifi2g_hw_filter_type, model_support_3g, isRuku, web_logo_path, language, language_support, language_show, user_web_theme, web_browser_title, esim_show, current_card_type, smartwifiSwich, onenet, dm_platform, web_page_hide, hide_no_account, account_level, login_times, network_mode, wifiCountrySelect, priorityStrategy, fw_switch, isDowngrade, wifi_show_display_config, onlyAuto, parentControlSwitch, tr069Function, pinStatus, wifiType, wlan5g_preferred, REAL_DEVICE_5G, clat, iad_ready_status, idu_dev_type, board_type, real_device, sim_status, antenna_status, PLMN, lock_plmn_flag, build_type, od_wifi_status, wifi_wps_capability, wifi_5g_bestselect, fwversion_type, id_wifi_status, batteryExist, ledNumber, network_smartwifi_conceal, nfcConceal, nfc_capability, usb_mount_capability, wifi_ssid_forbid, wifi_pwd_forbid, domain_value, redcap_network_capability, IMSI |
| 113 | GET_SYS_STATUS | network_type_str, signal_lvl, sim_status, wifi24g_enable, wifi5g_enable, modem_assert, wired_link_list, lock_pin_flag, lock_puk_flag, lock_device_flag, lock_plmn_flag, modem_at, idu_dev_type, board_type, data_switch, roam_status, flowLimitFlag, wired_wan_netstatus, wlan2g_wan_netstatus, wlan5g_wan_netstatus, pci_enable, voipStatus, volteStatus, network_operator, network_status, wanPrio, batteryExist, batteryCapacity, batteryLevel, batteryCharge, wanIndex, ipClash, fake_version, uptime, battery_abnormal_charging_detect, wired_iface_list, wlan2g_switch, wlan2g_switch_0, wlan2g_switch_1, wlan2g_switch_2, wlan2g_switch_3, wlan2g_switch_4, wlan5g_switch, wlan5g_switch_0, wlan5g_switch_1, wlan5g_switch_2, wlan5g_switch_3, wlan5g_switch_4, wifi_show_display_config, antenna_status_display, moblie_network_data_switch, device_inner_unit_info, voice_type, smartwifiSwich, mesh_agent, meshEnable, wifiDown, current_card_type, wanMode, wanStrategy, is_smartwifi_active_flag, flag, current_card_cfg_mode, netstatus_visual_sw, sms_sw, battery_percent_sw, ip_status, pci_manual_enable, pci_arrears_enable, sim_support_cap, flightMode, roamingEnable, usb_status |
| 115 | STATIC_LEASE | datas |
| 132 | WPS_CONFIG | wifiOpen, wlan2g_wps_switch |
| 133 | ROUTER_INFO | IMSI, CELL_ID, SINR, RSRP, RSSI, FREQ, RSRQ, uptime, device_sn, real_fwversion, fake_version, wan_dns, wan_dns2, wan_ipv6_ip, wan_ipv6_gateway, wan_ipv6_dns, wan_ipv6_dns2, wan_ip, wan_mac, wan_netmask, wan_gateway, wan_real_iface, wan_rx_packets, wan_tx_packets, wan_rx_bytes, wan_tx_bytes, module_imei, apn_name, PCI, CQI, dl_mcs, ul_mcs, network_type_str, PCI_5G, FREQ_5G, RSRP_5G, RSRQ_5G, SINR_5G, RSSI_5G, module_sn, reject_class, reject_cause, wlan2g_wan_netstatus, wlan5g_wan_netstatus, wlan_wan_ip, wlan_wan_netmask, wlan_wan_gateway, wlan_wan_mac, wlan_wan_dns, wlan_wan_dns2, wlan_wan_ipv6_ip, wlan_wan_ipv6_dns, wlan_wan_ipv6_dns2, wired_wan_real_interface, wired_wan_netstatus, wired_wan_ip, wired_wan_netmask, wired_wan_gateway, wired_wan_mac, wired_wan_dns, wired_wan_dns2, wired_wan_ipv6_ip, wired_wan_ipv6_gateway, wired_wan_ipv6_dns, wired_wan_ipv6_dns2, main_vice_antenna_status, hwversion, netWanRxBytes, netWanTxBytes, netWanRxRate, netWanTxRate, CELL_ID_5G, currentband, currentband_5g, bandwidth, bandwidth_5g, external_mcu_capabilty, external_mcu_version, ICCID, tm_operate_info_capability, wan_ipMode, wan_linkMode, lanIp, jt808_terminal_phone_number, jt808_terminal_id, jt808_nci_connect_status, jt808_tm_connect_status, gnss_hw_status, longitude, latitude, satellite_num, mobilePlat |
| 134 | QUERY_SIM_STATUS | lock_pin_flag, lock_puk_flag, pin_left_times, puk_left_times, lock_pin_switch |
| 164 | ROUTER_TABLE | (list/empty — needs page params) |
| 169 | DDNS | connectionStatus, domain |
| 172 | NETWORK_SERVICE | (list/empty — needs page params) |
| 202 | UPNP | data, upnpEnable |
| 205 | SYS_INFO_NETWORK | RSRP, RSRP_5G, RSSI, RSSI_5G, RSRQ, RSRQ_5G, SINR, PCI, FREQ, ENODEBID, CELL_ID, CQI, dl_mcs, dl_mcs_5g, ul_mcs, ul_mcs_5g, PLMN, signal_lvl, bandwidth, ECGI, network_type_str, network_operator, SINR_5G, PCI_5G, FREQ_5G, ENODEBID_5G, CELL_ID_5G, CQI_5G, bandwidth_5g, currentband, currentband_5g, ul64qam_support, dl256qam_support, max_ul_qam, max_dl_qam, max_ul_qam_5g, max_dl_qam_5g, rank_5g, rank_4g, flow_dl, flow_ul, bler_4g, bler_5g, mon_total_flow, current_real_wan_prio, onlineTime, onlineDuration, NCGI, ca_freq, ca_pci, ca_rsrp, ca_rsrq, ca_rssi, ca_state, lte_dl_cc, nr_dl_cc, ca_bandwidth, rscp, rssi3G, ecio, cellId3G, uarfcn, psc, currentband3G, tac_4g, tac_5g, bandwidth3G, dial_time, network_redcap, mimo_status, CA_COMBINATION, ecno, first_mode, systime |
| 207 | DEVICE_INFO | uptime, cpuload, memory, board_type, hwversion, build_date, real_fwversion, IMSI, config_version, module_type, module_softver, module_hardver, module_imei, module_sn, ICCID, device_sn, sim_slot, modem_assert, idu_firmware_version, idu_config_version, fake_version, temp_list, idu_dev_type, device_cmei, device_msisdn, idu_memory_availeble, idu_memory_level, real_device, cpu_usage, device_temperature, idu_connect_time, idu_disconnect_time, memoryFree, memoryLevel, flash_total, flash_availeble, flash_level, idu_flash_total, idu_flash_availeble, idu_flash_level, deviceTemperatureLevel, idu_hardware_version, idu_device_mac, idu_device_sn, lan_mac, efs_backup_restore, negotiatton_rate, voipclient_git_branch, voipclient_git_sha, voipclient_build_date, negotiatton_speed, eid, module_cp_softver, online_duration, device_maker, real_version |
| 208 | LAN_INFO | dhcp_end_ip, dhcp_start_ip, lan_dhcp_enable, lan_ip, lan_netmask, lan_real_iface, lan_mac |
| 209 | WIFI_INFO | wifi_interface, wifi_bridge, wifi_ssid, wifi_wme_enabled, wifi_max_num_sta, wifi_ignore_broadcast, wifi_wpa, wifi_wpa_passphrase, wifi_wpa_key_mgmt, wifi_wpa_pairwise, wifi1_interface, wifi1_bridge, wifi1_ssid, wifi1_wme_enabled, wifi1_max_num_sta, wifi1_ignore_broadcast, wifi1_wpa, wifi1_wpa_passphrase, wifi1_wpa_key_mgmt, wifi1_wpa_pairwise, wifi_enable, wifi_accesspoint, wifi_channel, wifi_mode |
| 210 | WIFI5G_INFO | wifi_interface, wifi_bridge, wifi_ssid, wifi_wme_enabled, wifi_max_num_sta, wifi_ignore_broadcast, wifi_wpa, wifi_wpa_passphrase, wifi_wpa_key_mgmt, wifi_wpa_pairwise, wifi1_interface, wifi1_bridge, wifi1_ssid, wifi1_wme_enabled, wifi1_max_num_sta, wifi1_ignore_broadcast, wifi1_wpa, wifi1_wpa_passphrase, wifi1_wpa_key_mgmt, wifi1_wpa_pairwise, wifi_enable, wifi_accesspoint, wifi_channel, wifi_mode |
| 211 | WIRELESS5G_CONFIG | wifiMode, wifiMasterSwitch, wifiSames, wifiOpen, broadcast, wifiwmm, cipherSuite, authenticationType, ssid, key, is_smartwifi_active_flag, ssid24G, original_ssid, wifimac, ssidList, wifiOpen_24g, wifiOpen_6g, mesh_switch, wifi5Channel |
| 213 | APN_CONFIG | mtu_max_range_limit, apnMtuPrefer, mtuFromGnbFail |
| 218 | NETWORKSET_MODE | networkMode, checkbox, mode5g, flightMode, dialMode, roamingEnable, deviceMode, lteCA, nrCA |
| 220 | DATA_ROAMING | roamingEnable |
| 222 | AUTO_DIAL | data_switch, dialMode |
| 226 | FLIGHT_MODE_INFO | flightMode, lteCA, nrCA |
| 230 | WIRELESS_ADVANCE | clientIsolation, performance, wifiOpen, txPower, countryCode, channel, wifiWorkMode, bandWidth, maxNum, connectNum, eliminateNum, wifiwmm, wifiMode, wifiPMF, maxNum5, apIsolateSw, wifi_power, domainEdit, maxNum24Limit, maxStaLimitCap24 |
| 231 | WIRELESS5G_ADVANCE | clientIsolation, performance, wifiOpen, txPower, countryCode, dfsSwitch, channel, wifiWorkMode, bandWidth, maxNum, connectNum, eliminateNum, wifiwmm, wifiMode, wifiPMF, maxNum24, apIsolateSw, wifi_power, domainEdit, maxNumLimit, maxStaLimitCap5 |
| 242 | WLAN_5G_TPC | tpcSwitch |
| 251 | IP_PASSTHROUGH | ipPassSwitch, nicType, mode, mac, networkMode |
| 263 | TIME_TO_RESTART | timrRestartSwitch, restartHour, restartMinute, restartSecond, restartWeek, restartDate, restartMode, restartDelaySw, restartDelayTime, periodicRebootSw, periodicRebootHours, idleStartTime, idleEndTime, busyStDetectSw, time_reboot_delay_flag, time_reboot_delay_proc_time |
| 275 | DDOS_PROTECTION | ddosSwich, ackFloodSwich |
| 282 | GET_NEIGHBOR_INFO | lte_info, nr_info, wcdmaInfo, network_type_str |
| 285 | WAN_WLAN_MODE | networkMode, priorityStrategy, wlanSsid, wlanPassword, wlanEncry |
| 287 | UE_CONFIG | srs_sw, lteSwitch, nrSwitch |
| 302 | BROADBAND_SETTING | networkMode, IpMode, priorityStrategy, uisMode, LinkMode, IpVersion, MacClone, IpAddr4, Submask4, Gateway4, FirstDns4, SecDns4, MTU, MRU, NatEnable, UserName, PassWd, dhcpSw, dhcpBackWs, backupGateway, backupNetmask, backupDns1, backupDns2, smsNoticeSw, voiceNoticeSw, noticePhoneNumber, noticeInterval, Dhcpv6Mode, IpAddr6, IpAddr6Len, Gateway6, FirstDns6, SecDns6, vlanId, wanType, dhcpAutoGateway, dhcpAutoNetmask, dhcpAutoDns1, dhcpAutoDns2, mtu_max_range_limit |
| 315 | NETWORK_TOPOLOPY | (list/empty — needs page params) |
| 322 | LOG_INFO | logGrade, logSize, logSizeLimit, logBackup |
| 333 | FW_UPNP_STATUS | upnpSwitch |
| 336 | WAN_LINK_DETECT_SETTING | wanLinkDetectSwitch, checkWanLinkDetectMode, wanLinkDetectIP1, wanLinkDetectIP2, wanLinkDetectIP3, wanLinkDetectCheckTime, LinkDetectAction, reboot_wait_time, dial_wait_time, dnsv4_server_sw, dnsv6_server_sw, dnsv4_server1, dnsv4_server2, dnsv4_server3, dnsv6_server1, dnsv6_server2, dnsv6_server3, wan_mode, selectType, LinkMode, IpVersion |
| 337 | TRAFFIC_LIMIT_SETTING | limitSwitch, limitSize, startDate, nation_limit_size, internation_limit_size, nation_warn_percentage, warn_percentage, internation_warn_percentage, flow_sms_notice_sw, national_flow_sms_notice_sw, international_flow_sms_notice_sw, flow_limit_unit, flow_notice_number, nation_flow_notice_number, internation_flow_notice_number, nation_flow_notice_text, internation_flow_notice_text, flow_notice_text, reset_traffic_lastTime, mon_download_flow, roam_dl_mon_flow, roam_ul_mon_flow, ul_mon_flow, dl_mon_flow |
| 340 | UNLOCK_SIM | remainCount, functionSwitch |
| 367 | FW_SWITCH_SETTING | fw_switch, level |
| 382 | PARENTAL_CONTROL_SPEED | (list/empty — needs page params) |
| 383 | PARENTAL_CONTROL_URL | (list/empty — needs page params) |
| 385 | PARENTAL_CONTROL_TIME | (list/empty — needs page params) |
| 394 | GET_STATUS_BAR | signal_lvl, network_type_str, sim_status, flowLimitFlag, lock_device_flag, lock_plmn_flag, roam_status, data_switch, wired_link_list, modem_assert, modem_at, pci_enable, network_status, wanPrio, voipStatus, volteStatus, fanSpeedStatus, ipClash, wanIndex, batteryExist, batteryCapacity, batteryLevel, batteryCharge, meshRole, receive_full, meshStatus, battery_temp_protect_status, battery_abnormal_charging_detect, wired_iface_list, wpsStatus, wlan2g_switch, pci_manual_enable, pci_arrears_enable, wlan2g_switch_0, wlan5g_switch, wlan5g_switch_0, current_card_type, flag, wifiDown, wanMode, wanStrategy, voice_type, voice_auto_sw, meshEnable, current_card_cfg_mode, is_smartwifi_active_flag, battery_percent_sw, sim_support_cap, ip_status, flightMode, roamingEnable, sms_unread |
| 400 | GET_SYSTEM_PORT | systemPort |
| 401 | GET_DASHBOARD_INDEX | networkMode, flightMode, dialMode, dhcp_list_info, flow_dl, flow_ul, mon_total_flow, network_operator, network_type_str, signal_lvl, RSRP, RSRP_5G, sentSpeed, receiveSpeed, mac_lte_ul, mac_lte_dl, mac_nr_ul, mac_nr_dl |
| 405 | TRAFFIC_SETTING | can_count, can_list |
| 411 | WIRELESS_POWER | txPower, txPower5g |
| 417 | WIFI_INIT_STATUS | wifiStatus |
| 484 | DEVICE_SELF_CHECK | board_type, sim_status, network_type_str, signal_level, RSRP_5G, RSRP, currentband_5g, currentband, pdpType, wan_ip, wan_ipv6, lockPlmn, lock_5g_flag, lock_4g_flag, band_5g_switch, band_4g_switch, dhcpServer, wlan2g_switch, wlan2g_channel, wlan2g_bandwidth, maxNum24, wlan24g_connections, wla5g_switch, wlan5g_channel, wlan5g_bandwidth, maxNum5, wlan5g_connections, dfs_switch, deviceTemperatureLevel, deviceMemoryLevel, deviceFlashLevel, antenna_status, voipSw, volteSw, volteRegStatus, voipRegStatus, voipAuthStatus, voiceDriverStatus, dialMode, roamingEnable |
| 502 | TRUSTED_IP_SETTING | trusted_ip_sw, trusted_ip_0, trusted_ip_1, trusted_ip_2, trusted_ip_3, trusted_ip_4, trusted_ip_5, trusted_ip_6, trusted_ip_7, trusted_ip_8, trusted_ip_9, trusted_ip_10, trusted_ip_11, trusted_ip_12, trusted_ip_13, trusted_ip_14 |
| 503 | WIFI_SCHEDULE_SETTING | wifiSwitch, wifi_on_start_hour, wifi_on_start_min, wifi_off_start_hour, wifi_off_start_min |
| 504 | ALG_SETTING | L2TPSW_Flag, IPSecSW_Flag, H323SW_Flag, RTSPSW_Flag, SIPSW_Flag, FTPSW_Flag, PPTPSW_Flag |

## Needs parameters, or timed out

| cmd | name | note |
|---|---|---|
| 0 | SYS_INFO | needs extra params (see web UI page) |
| 4 | USER_INFO | needs extra params (see web UI page) |
| 10 | FIREWALL | needs extra params (see web UI page) |
| 17 | SYS_LOG | timed out / needs params |
| 82 | GET_LTE_STATUS | needs extra params (see web UI page) |
| 114 | GET_PLMN_NUMBERS | needs extra params (see web UI page) |
| 118 | IPV6_CONFIG | needs extra params (see web UI page) |
| 157 | GET_SUPPORT_BANDS | needs extra params (see web UI page) |
| 175 | DNS_CONFIG | timed out / needs params |
| 203 | FLOW_MONITORING | needs extra params (see web UI page) |
| 206 | WAN_INFO | needs extra params (see web UI page) |
| 214 | GET_DMZ | needs extra params (see web UI page) |
| 215 | FW_UPNP | needs extra params (see web UI page) |
| 216 | STATIC_ARP_BINDING | needs extra params (see web UI page) |
| 217 | ACL_FILTER | needs extra params (see web UI page) |
| 227 | DATA_SWITCH | timed out / needs params |
| 286 | CELL_PUNISHMENT | timed out / needs params |
| 407 | TRAFFIC_DAY_STAT | needs extra params (see web UI page) |
| 408 | TRAFFIC_HOUR_STAT | needs extra params (see web UI page) |

## LIMITED_ACCESS for the user account (do not build UI for these)

25 SPEED_LIMIT, 130 MULTI_APN_INFO, 219 LOCK_PLMN, 234 SYS_SERVICE_SET, 273 GET_ENDC_INFO, 278 WIRELESS_MACFILTER, 291 MULTI_WAN_INFO, 314 MESH_SETTING, 328 UNORMAL_REBOOT_SWITCH, 334 REMOTE_WEBPORT_SETTING, 345 FW_ACCESS_CONTROL_SIMPLE, 351 DST_SETTING, 363 CHANGE_ANTENNA_STATUS, 371 SLEEP_SETTINGS, 378 LIGHTS_OUT_SETTING, 406 TRAFFIC_INTERFACE_LIST, 506 GAME_TRAFFIC_ACCELERATION

## Not probed (actions — never call speculatively)

6 SYS_REBOOT, 99/120 REBOOT, 112 RESTORE_DEFAULT, 116 SEARCH_PLMN, 228 SCCAN_PLMN, 288 RESCAN_NETWORK, 342 PCI_SCAN, 48/49 NET_CONNECT/DISCONNECT, 5/150/239 firmware update, 3 UPLOAD_FILE, 13/14 SMS send/delete, 45/270 AT commands, 237 ADB_SWITCH, 409 SSH.

## Write schemas

Every `POST` write goes through `SettingsWriter.write(WriteSpec(...))` (`GET` read -> preflight -> `cmd 233` write token -> single `POST` -> `GET` verify without blind `POST` retry). All bodies include `cmd`, `method: "POST"`, `sessionId`, and `token` alongside the fields below.

### Verified in repo + `~/airtelOdu-webui/`

| cmd | Const | Web UI source | Verified POST fields | Verify `GET` / check |
|---:|---|---|---|---|
| 2 | `WIRELESS_CONFIG` | `js/step.js:128-167`, `js/step_rules.js:1-100`, `WifiRepository.kt`, `ZltRouterClientTest.kt` | `subcmd: 0`, `wifiOpen` (`"1"`/`"0"`), `broadcast` (`"1"` visible / `"0"` hidden), `ssid` (Base64 UTF-8, 1–32 UTF-8 bytes, no leading/trailing spaces, no `;` or `\`), `key` (8–63 chars, no `"`, `;`, `\`, or whitespace when `authenticationType != "0"`), `authenticationType` (`"0"` Open System, else WPA-PSK e.g. `"2"`), optional `wifiSames` (`"1"`/`"0"`), plus preserved 2.4 GHz keys from `GET cmd 2 subcmd 0` | `cmd 2 subcmd 0` |
| 211 | `WIRELESS5G_CONFIG` | `js/step.js:134-167`, `js/step_rules.js:1-100`, `WifiRepository.kt`, `ZltRouterClientTest.kt` | `subcmd: 0`, `wifiOpen` (`"1"`/`"0"`), `broadcast` (`"1"` visible / `"0"` hidden), `ssid` (Base64 UTF-8), `key`, `authenticationType`, optional `wifiSames`, plus preserved 5 GHz keys from `GET cmd 211 subcmd 0` | `cmd 211 subcmd 0` |
| 3 | `NETWORK_CONFIG` | `LanRepository.kt`, `fixtures/3.json`, `ZltRouterClientTest.kt` | Preserved LAN/DHCP keys (`lanIp`, `netMask`, `dhcpServer`, `main_dns`, `vice_dns`, `ipBegin`, `ipEnd`, `expireTime`, `ipv6_mode`, `ipv6_startIp`, `ipv6_endIp`, `ipv6_main_dns`, `ipv6_vice_dns`, plus `bindPort0` when `supportList` present) updating DNS / DHCP / LAN fields as requested | `cmd 3` |
| 6 | `SYS_REBOOT` | `js/main.js:927-932` | `rebootType` (`1` normal reboot, `2` config change, `3` restore, `4` cancel, `5` update) | Re-login / `cmd 113` recovery |
| 12 | `SMS_INFO` (mark read) | `js/sms/smsInbox.js:56-108,190-217` | `method: "POST"`, `index: "<id>"` / comma-joined IDs / `"READ ALL"`. Note list reads use `page_num: <1-based>`, `subcmd: 0` (Inbox) / `1` (Outbox) returning `sms_total`, `receive_full`/`send_full`, and comma-joined Base64 rows `index flag phone($->space) date time text...` | `cmd 12` (`subcmd: 0`, `page_num`) |
| 13 | `SMS_DELETE` | `js/sms/smsInbox.js:137-156,218-261`, `js/sms/smsOutbox.js:107-126,158-204` | `method: "POST"`, `subcmd: 0` (Inbox) / `1` (Outbox), `index: "<id>"` / comma-joined IDs / `"DELETE ALL"` (`message == "0"` confirms) | `cmd 12` + `cmd 394` |
| 14 | `SMS_SEND` | `js/sms/smsInbox.js:157-189`, `js/sms/smsOutbox.js:127-157` | `method: "POST"`, `phoneNo` (`/^[\d+]\d{1,30}$/`), `content` (Base64 UTF-8, max weighted length 4096 where `< 0x7F` = 1 and `>= 0x7F` = 2; reject UTF-16 surrogates `"D83"`; `message == "502"` = storage full, `"0"` ok) | `cmd 12` (`subcmd: 1`) |
| 16 | `SMS_SETTING` | `js/sms/smsSetting.js:33-81` | `method: "POST"`, `smsSw: "1"\|"0"`, `maxSize: "50..1000"`, optional `dmCsca` (included only when `cmd 16` GET returns `smscSw == "1"`, where `smsSetting.js` sets `!vm.smscSw`) | `cmd 16` GET |
| 17 | `SYS_LOG` | `js/manage/sysLog.js:14-58` | `method: "POST"` clears syslog (GET returns newline-delimited raw text lines) | `cmd 17` GET |
| 51 | `MODEM_CMD` | `js/step.js:212-328`, `js/home.js:568-605`, `js/step_rules.js:177-205` | SIM PIN management: `subcmd: "1"` toggle PIN lock (`enable`: `"1"`/`"0"`, `pin`: 4–8 digits); `subcmd: "2"` unlock SIM PIN (`pin`: 4–8 digits); `subcmd: "3"` PUK unlock + set new PIN (`puk`: 8 digits, `pin`: 4–8 digits, `pukPassword1`, `pukPinPassword1`, `pukPinPasswordConfirm1`); `subcmd: "4"` change PIN (`enable: "2"`, `pin`, `passwordChange`, `passwordConfirm`, `pinPasswordChange`) | `message == "0"` + `cmd 134` (`QUERY_SIM_STATUS`) |
| 102 | `CHANGE_PASSWD` | `js/index.js:1-118` | `method: "POST"`, `subcmd` (`3` when login `level == "2"`, else `0`), `old_passwd` (Base64), `newPasswd` (Base64; 8–63 printable ASCII chars, no spaces, != old password, requires upper + lower + digit + symbol); `message == "success"` confirms (`"passwd error"` on mismatch) | `message == "success"` + re-login |
| 160 | `LOCK_PHY_CELL` | `SignalRepository.kt` | `subcmd: "0"` LTE (`lte_lock_sw`, `lte_lock_freq`, `lte_lock_pci`, `lte_lock_max_num`) / `subcmd: "1"` NR (`nr_lock_sw`, `nr_lock_band`, `nr_lock_freq`, `nr_lock_pci`, `nr_lock_max_num`) | `cmd 160` |
| 161 | `LOCK_BAND` | `SignalRepository.kt` | `lock_4g_flag`, `lock_5g_flag`, `lock_4g_list` (hex bitmask), `lock_5g_list` (hex bitmask), `lock_4g_max_num`, `lock_5g_max_num` | `cmd 161` |
| 168 | `NETWORK_TOOLS` | `ZltRouterClientTest.kt` | Ping start (`subcmd: 0`, `url`, `pingTimes`), Traceroute (`subcmd: 2`, `url`, `stopped: "0"`) | `cmd 204 subcmd 168` |
| 222 | `AUTO_DIAL` | `MobileRepository.kt` | `dialMode` (`"0"` on, `"1"` off per observed firmware mapping) | `cmd 218` |
| 226 | `FLIGHT_MODE_INFO` | `MobileRepository.kt` | `flightMode` (`"1"` on, `"0"` off) | `cmd 218` |
| 263 | `TIME_TO_RESTART` | `js/sys/timeToRestart.js:43-122` | `method: "POST"`, `timrRestartSwitch: "1"` with `restartHour` (`"HH"`), `restartMinute` (`"mm"`), `restartSecond` (`"ss"`), `restartWeek` (comma-joined `"1,2,3,4,5,6,0"` where `0`=Sun), or `timrRestartSwitch: "0"` to disable | `cmd 263` GET |
| 288 | `RESCAN_NETWORK` | `MobileRepository.kt` | Empty extra POST (`cmd: 288`, `method: "POST"`) | Status message |
| 322 | `LOG_INFO` | `js/manage/logInfo.js:34-94` | `method: "POST"`, `logGrade` (`"0"` Default .. `"4"` Info), `logSize` (`1..2000` KB * `1000` bytes) | `cmd 322` GET |
| 367 | `FW_SWITCH_SETTING` | `js/firewall/fw_switch_setting.js:43-50` | `method: "POST"`, `fw_switch` (`"1"` / `"0"`), `level` (`"low"` / `"medium"` / `"high"`), `success: true` | `cmd 367` GET |
| 504 | `ALG_SETTING` | `js/config/algSetting.js:5-61` | `method: "POST"` with all 7 switches (`L2TPSW_Flag`, `IPSecSW_Flag`, `H323SW_Flag`, `RTSPSW_Flag`, `SIPSW_Flag`, `FTPSW_Flag`, `PPTPSW_Flag`, `"1"` / `"0"`) | `cmd 504` GET |

### Pending / uncaptured second-hop sub-pages in `~/airtelOdu-webui`

`~/airtelOdu-webui/` captured top-level `MenuItem` `*Index.html` / `*Index.js` wrappers (`16` MenuItem URLs returned 404). Second-hop tab pages loaded dynamically via `secondMenuClick(items, id)` are absent from `~/airtelOdu-webui/`, so their `SET` schemas remain unverified and **must not be guessed**:

- **Phase 2 (`Devices`) missing sub-pages:**
  - `cmd 115` (`STATIC_LEASE`): `html/config/dhcpSetting.html`, `html/config/dhcpSettingMulti.html` (+ matching JS) referenced by `js/config/dhcpIndex.js`.
  - `cmd 23` / `cmd 30` (`MAC_FILTER` / `MAC_DEFAULT_FILTER`): `html/firewall/macFilter.html`, `html/firewall/defaultMac.html` (+ matching JS) referenced by `js/firewall/macIndex.js`.
  - `cmd 278` (`WIRELESS_MACFILTER`): `html/config/wifiList24g.html`, `html/config/wifiList5g.html` (+ matching JS) referenced by `js/config/wifiListIndex.js`.
  - `cmd 382` / `383` / `385` / `391` (`PARENTAL_CONTROL_*` / `PARENT_MODEL`): `html/parent/parentControl.html`, `parentSpeed.html`, `parentTime.html`, `parentUrl.html` (+ matching JS). Note `cmd 391` UI is also gated behind `Page.parentControl == "1"` (`cmd 80` `parentControlSwitch`) and `pageHideCheck(153)`.
- **Phases 3–5 missing sub-pages (to be checked before implementing those writes):**
  - **Phase 3 (`Wi-Fi & Mobile & LAN`):** `cmd 411` (`wifiPower.html/.js`, `wlan24gAdvance.html/.js`, `wlan5gAdvance.html/.js`), `cmd 132` (`wirelessWpsSetting.html/.js`), `cmd 503` (`wifiToRestart.html/.js`), `cmd 209`/`210` (`wifi24g.html`, `wifi5g.html`), `cmd 218` SET (`networkSet.html/.js`), `cmd 220` SET (roaming), `cmd 213` (`apnConfig.html/.js`).
  - **Phase 4 (`Messages, USSD & Traffic`):** `cmd 350` (`html/manage/ussd.html/.js` referenced by `js/manage/ussdIndex.js`), `cmd 337` SET (`html/config/trafficLimit_settings.html/.js` referenced by `js/config/traffic_settingIndex.js`), and `cmd 405–408` stats UI (`html/industry/trafficStat.html/.js` referenced by `js/industry/trafficStatIndex.js`). Daily usage history falls back to local snapshot recording when router day/hour stat tables are omitted.
  - **Phase 5 (`Security & System`):** `cmd 214`/`215`/`275`/`202`/`21`/`22`/`26`/`502` (`dmz.html`, `upnp.html`, `ddos.html`, `portForward.html`, `ipFilter.html`, `urlFilter.html`), `cmd 11` SET (`timeSet.html`), `cmd 336` (`wanLinkDetect.html`), and standalone `cmd 112` (`restoreFactory.html`). Those uncaptured write schemas remain omitted rather than guessed.
