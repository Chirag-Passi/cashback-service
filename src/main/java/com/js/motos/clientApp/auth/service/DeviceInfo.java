package com.js.motos.clientApp.auth.service;

import jakarta.servlet.http.HttpServletRequest;

import com.js.motos.clientApp.common.RequestUtils;

/** Client metadata recorded against a session, for the "your devices" screen. */
public record DeviceInfo(String deviceId, String deviceName, String userAgent, String ip) {

    public static DeviceInfo from(HttpServletRequest request, String deviceId, String deviceName) {
        return new DeviceInfo(
                RequestUtils.truncate(deviceId, 128),
                RequestUtils.truncate(deviceName, 150),
                RequestUtils.userAgent(request),
                RequestUtils.clientIp(request));
    }
}