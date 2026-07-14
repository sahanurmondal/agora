package com.agora.catalog.media;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Media flow: the service never proxies bytes. Clients get a presigned PUT to
 * upload straight to MinIO (S3 pattern), and reads go through the nginx
 * pull-through cache (compose profile `media`, host :8099) — X-Cache: HIT is
 * the CDN demo.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MinioClient minio;
    private final String bucket;
    private final String cdnBase;

    public MediaController(@Value("${catalog.minio.endpoint}") String endpoint,
                           @Value("${catalog.minio.access-key}") String accessKey,
                           @Value("${catalog.minio.secret-key}") String secretKey,
                           @Value("${catalog.minio.bucket}") String bucket,
                           @Value("${catalog.cdn-base}") String cdnBase) {
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        this.cdnBase = cdnBase;
    }

    @PostMapping("/upload-url")
    public Map<String, String> presignUpload(@RequestParam(defaultValue = "bin") String ext) throws Exception {
        String key = UUID.randomUUID() + "." + ext;
        String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT).bucket(bucket).object(key)
                .expiry(10, TimeUnit.MINUTES).build());
        return Map.of("key", key, "upload_url", url, "cdn_url", cdnBase + "/" + bucket + "/" + key);
    }
}
