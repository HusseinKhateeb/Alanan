package com.alanan.accounting.backup;

import com.alanan.accounting.auth.AuthUser;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.file.*;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService service;
    public BackupController(BackupService service) { this.service=service; }

    @GetMapping
    public List<Map<String,Object>> list() { return service.list(); }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> export(@AuthenticationPrincipal AuthUser actor) { return service.export(actor); }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> upload(@RequestPart("file") MultipartFile file,
        @RequestParam(required=false) String expectedSha256,@AuthenticationPrincipal AuthUser actor) {
        return service.upload(file,expectedSha256,actor);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id,@AuthenticationPrincipal AuthUser actor) throws IOException {
        Path archive=service.download(id,actor);
        long size=Files.size(archive);
        StreamingResponseBody body=output -> { try(var input=Files.newInputStream(archive)) { input.transferTo(output); } };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(size)
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"alanan-"+id+".dump\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);
    }

    @GetMapping(value="/{id}/checksum",produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> checksum(@PathVariable UUID id,@AuthenticationPrincipal AuthUser actor) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"alanan-"+id+".dump.sha256\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(service.checksum(id,actor));
    }

    @PostMapping("/{id}/restore-test")
    public Map<String,Object> testRestore(@PathVariable UUID id,@AuthenticationPrincipal AuthUser actor) {
        return service.testRestore(id,actor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id,@AuthenticationPrincipal AuthUser actor) { service.remove(id,actor); }
}
