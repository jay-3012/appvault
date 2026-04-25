package com.appvault.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GcsStorageService {

    @Value("${gcp.storage.bucket}")
    private String bucketName;

    private Storage storage;

    private Storage getStorage() throws IOException {
        if (storage == null) {
            storage = StorageOptions.newBuilder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build()
                    .getService();
        }
        return storage;
    }

    public String uploadApk(String appId, String versionId,
                             MultipartFile file) throws IOException {
        String gcsPath = String.format("apks/%s/%s/app.apk", appId, versionId);
        uploadFile(gcsPath, file.getBytes(), "application/vnd.android.package-archive");
        log.info("APK uploaded to GCS: {}", gcsPath);
        return gcsPath;
    }

    public String uploadIcon(String appId, MultipartFile file) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        String gcsPath = String.format("icons/%s/icon.%s", appId, ext);
        uploadFile(gcsPath, file.getBytes(), file.getContentType());
        return gcsPath;
    }

    public String uploadScreenshot(String appId, String filename,
                                    MultipartFile file) throws IOException {
        String gcsPath = String.format("screenshots/%s/%s", appId, filename);
        uploadFile(gcsPath, file.getBytes(), file.getContentType());
        return gcsPath;
    }

    public String generateSignedDownloadUrl(String gcsPath,
                                             int ttlMinutes) throws IOException {
        BlobInfo blobInfo = BlobInfo.newBuilder(
                BlobId.of(bucketName, gcsPath)).build();

        URL signedUrl = getStorage().signUrl(
                blobInfo,
                ttlMinutes,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature()
        );
        return signedUrl.toString();
    }

    public String getGcsUrl(String gcsPath) {
        return "gs://" + bucketName + "/" + gcsPath;
    }

    private void uploadFile(String gcsPath, byte[] bytes,
                             String contentType) throws IOException {
        BlobId blobId = BlobId.of(bucketName, gcsPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();
        getStorage().create(blobInfo, bytes);
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "png";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
