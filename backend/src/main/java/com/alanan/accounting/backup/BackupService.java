package com.alanan.accounting.backup;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BackupService {
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOC_BYTES = 8L * 1024 * 1024;
    private static final Set<String> REQUIRED_TABLES = Set.of("flyway_schema_history", "users", "products", "purchase_invoices", "sales");
    private final JdbcTemplate jdbc;
    private final Path root;
    private final String dumpCommand, restoreCommand, dbUrl, dbUsername, dbPassword;

    public BackupService(JdbcTemplate jdbc,
        @Value("${app.backup.staging-directory}") String directory,
        @Value("${app.backup.pg-dump}") String dumpCommand,
        @Value("${app.backup.pg-restore}") String restoreCommand,
        @Value("${spring.datasource.url}") String dbUrl,
        @Value("${spring.datasource.username:}") String dbUsername,
        @Value("${spring.datasource.password:}") String dbPassword) {
        this.jdbc=jdbc; this.root=Path.of(directory).toAbsolutePath().normalize();
        this.dumpCommand=dumpCommand; this.restoreCommand=restoreCommand;
        this.dbUrl=dbUrl; this.dbUsername=dbUsername; this.dbPassword=dbPassword;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT id,original_name,source,size_bytes,sha256,created_at FROM backup_archives ORDER BY created_at DESC,id DESC LIMIT 50")
            .stream().map(row -> {
                var result=new LinkedHashMap<>(row);
                result.put("id",row.get("id").toString());
                return (Map<String,Object>)result;
            }).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> export(AuthUser actor) {
        UUID id=UUID.randomUUID();
        Path partial=path(id,".partial"), archive=path(id,".dump");
        try {
            Files.createDirectories(root);
            ProcessBuilder process=new ProcessBuilder(dumpCommand,"--format=custom","--no-owner","--no-acl","--file="+partial);
            databaseEnvironment(process.environment());
            run(process,Duration.ofMinutes(10),"BACKUP_EXPORT_FAILED");
            validate(partial);
            Files.move(partial,archive,StandardCopyOption.ATOMIC_MOVE);
            return register(id,"alanan-"+id+".dump","EXPORT",actor,archive);
        } catch(Exception ex) {
            deleteQuietly(partial); deleteQuietly(archive);
            if(ex instanceof ApiException api) throw api;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"BACKUP_EXPORT_FAILED");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> upload(MultipartFile file, String expectedSha256, AuthUser actor) {
        if(file.isEmpty() || file.getSize()>MAX_ARCHIVE_BYTES || file.getSize()<5)
            throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_INVALID");
        if(expectedSha256!=null && !expectedSha256.isBlank() && !Pattern.matches("[a-fA-F0-9]{64}",expectedSha256.strip()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_CHECKSUM_INVALID");
        UUID id=UUID.randomUUID();
        Path partial=path(id,".partial"), archive=path(id,".dump");
        try {
            Files.createDirectories(root);
            try(InputStream in=file.getInputStream(); OutputStream out=Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[64*1024]; long total=0; int read;
                while((read=in.read(buffer))!=-1) {
                    total+=read;
                    if(total>MAX_ARCHIVE_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_INVALID");
                    out.write(buffer,0,read);
                }
            }
            validate(partial);
            String hash=sha256(partial);
            if(expectedSha256!=null && !expectedSha256.isBlank() && !hash.equalsIgnoreCase(expectedSha256.strip()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_CHECKSUM_INVALID");
            Files.move(partial,archive,StandardCopyOption.ATOMIC_MOVE);
            return register(id,"uploaded-"+id+".dump","UPLOAD",actor,archive);
        } catch(Exception ex) {
            deleteQuietly(partial); deleteQuietly(archive);
            if(ex instanceof ApiException api) throw api;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"BACKUP_UPLOAD_FAILED");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Path download(UUID id, AuthUser actor) {
        var rows=jdbc.queryForList("SELECT id,sha256 FROM backup_archives WHERE id=?",id);
        if(rows.isEmpty() || !Files.isRegularFile(path(id,".dump"))) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        audit(id,"DOWNLOAD",actor,Map.of("sha256",rows.getFirst().get("sha256")));
        return path(id,".dump");
    }

    @PreAuthorize("hasRole('ADMIN')")
    public String checksum(UUID id, AuthUser actor) {
        var rows=jdbc.queryForList("SELECT sha256 FROM backup_archives WHERE id=?",id);
        if(rows.isEmpty() || !Files.isRegularFile(path(id,".dump"))) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        String hash=(String)rows.getFirst().get("sha256");
        audit(id,"CHECKSUM_DOWNLOAD",actor,Map.of("sha256",hash));
        return hash+"  alanan-"+id+".dump\n";
    }

    @PreAuthorize("hasRole('ADMIN')")
    public void remove(UUID id, AuthUser actor) {
        var rows=jdbc.queryForList("SELECT sha256 FROM backup_archives WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        try { Files.deleteIfExists(path(id,".dump")); }
        catch(IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"BACKUP_DELETE_FAILED"); }
        audit(id,"REMOVE",actor,Map.of("sha256",rows.getFirst().get("sha256")));
        jdbc.update("DELETE FROM backup_archives WHERE id=?",id);
    }

    private Map<String,Object> register(UUID id,String name,String source,AuthUser actor,Path archive) throws IOException {
        long size=Files.size(archive); String hash=sha256(archive);
        jdbc.update("INSERT INTO backup_archives(id,original_name,source,size_bytes,sha256,created_by) VALUES (?,?,?,?,?,?)",
            id,name,source,size,hash,actor.id());
        audit(id,source,actor,Map.of("sha256",hash,"sizeBytes",size));
        return Map.of("id",id.toString(),"original_name",name,"source",source,"size_bytes",size,"sha256",hash);
    }

    private void validate(Path archive) throws IOException, InterruptedException {
        if(Files.size(archive)<5 || Files.size(archive)>MAX_ARCHIVE_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_INVALID");
        try(InputStream in=Files.newInputStream(archive)) {
            if(!Arrays.equals(in.readNBytes(5),"PGDMP".getBytes(StandardCharsets.US_ASCII)))
                throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_INVALID");
        }
        Path toc=path(UUID.randomUUID(),".toc");
        try {
            ProcessBuilder process=new ProcessBuilder(restoreCommand,"--list",archive.toString());
            process.redirectOutput(toc.toFile());
            run(process,Duration.ofMinutes(2),"BACKUP_INVALID");
            if(Files.size(toc)>MAX_TOC_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_INVALID");
            String contents=Files.readString(toc);
            for(String table:REQUIRED_TABLES)
                if(!contents.contains(" TABLE public "+table+" "))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"BACKUP_SCHEMA_INVALID");
        } finally { deleteQuietly(toc); }
    }

    private void run(ProcessBuilder builder,Duration timeout,String failure) throws IOException,InterruptedException {
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if(builder.redirectOutput()==ProcessBuilder.Redirect.PIPE) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        if(!process.waitFor(timeout.toSeconds(),TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,failure);
        }
        if(process.exitValue()!=0) throw new ApiException(HttpStatus.BAD_REQUEST,failure);
    }

    private void databaseEnvironment(Map<String,String> env) {
        try {
            URI uri=URI.create(dbUrl.substring("jdbc:".length()));
            if(!"postgresql".equals(uri.getScheme()) || uri.getHost()==null || uri.getPath()==null || uri.getPath().length()<2)
                throw new IllegalArgumentException();
            var query=new HashMap<String,String>();
            if(uri.getRawQuery()!=null) for(String part:uri.getRawQuery().split("&")) {
                String[] pair=part.split("=",2);
                if(pair.length==2) query.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
            }
            env.put("PGHOST",uri.getHost()); env.put("PGPORT",String.valueOf(uri.getPort()<0 ? 5432 : uri.getPort()));
            env.put("PGDATABASE",uri.getPath().substring(1));
            String username=dbUsername.isBlank() ? query.get("user") : dbUsername;
            String password=dbPassword.isBlank() ? query.get("password") : dbPassword;
            if(username==null || password==null) throw new IllegalArgumentException();
            env.put("PGUSER",username); env.put("PGPASSWORD",password);
            if(query.containsKey("sslmode")) env.put("PGSSLMODE",query.get("sslmode"));
            if(query.containsKey("channelBinding")) env.put("PGCHANNELBINDING",query.get("channelBinding"));
        } catch(RuntimeException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"BACKUP_CONFIG_INVALID"); }
    }

    private String sha256(Path file) throws IOException {
        try(InputStream in=Files.newInputStream(file)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[64*1024]; int read;
            while((read=in.read(buffer))!=-1) digest.update(buffer,0,read);
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private Path path(UUID id,String suffix) { return root.resolve(id+suffix); }
    private void deleteQuietly(Path file) { try { Files.deleteIfExists(file); } catch(IOException ignored) { } }
    private void audit(UUID id,String action,AuthUser actor,Map<String,Object> details) {
        jdbc.update("INSERT INTO backup_events(archive_id,action,user_id,details) VALUES (?,?,?,jsonb_build_object('sha256',CAST(? AS text),'sizeBytes',CAST(? AS bigint)))",
            id,action,actor.id(),details.get("sha256"),details.getOrDefault("sizeBytes",null));
    }
}
