package com.appvault.scanner.dto;

import lombok.Data;
import java.util.List;

@Data
public class ScanResultCallback {
    private String appId;
    private String versionId;
    private String callbackId;
    private String scanStatus; // PASSED | FLAGGED | REJECTED
    private int riskScore;
    private List<String> flags;
    private String certFingerprint;
    private String packageName;
    private List<String> permissions;
    private int minSdk;
    private int targetSdk;
    private String error;
}