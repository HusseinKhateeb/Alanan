package com.alanan.accounting.backup;

import com.alanan.accounting.auth.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class BackupIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17.9-alpine");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final Path STAGED=Path.of(System.getProperty("java.io.tmpdir"),"alanan-backup-test-"+UUID.randomUUID());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl); p.add("spring.datasource.username",db::getUsername); p.add("spring.datasource.password",db::getPassword);
        p.add("app.auth.jwt-secret",()->Base64.getEncoder().encodeToString((PASSWORD+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        p.add("app.bootstrap.enabled",()->true); p.add("app.bootstrap.username",()->"owner"); p.add("app.bootstrap.password",()->PASSWORD);
        p.add("app.backup.staging-directory",STAGED::toString);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired AdminBootstrap bootstrap; @Autowired IdentityRepository identities; @Autowired TokenService tokens;
    @Autowired BackupService backups;
    String token; UUID owner;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        bootstrap.run(new DefaultApplicationArguments());
        owner=identities.credentials("owner").orElseThrow().id();
        token=tokens.issue(identities.activeUser(owner,0).orElseThrow(),0).accessToken();
    }
    @AfterAll static void cleanup() throws Exception {
        if(Files.isDirectory(STAGED)) try(var files=Files.list(STAGED)) {
            for(Path file:files.toList()) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(STAGED);
    }
    @Test void invalidUploadAndNonAdminAccessCannotChangeStagedFiles() throws Exception {
        var fake=new MockMultipartFile("file","fake.dump","application/octet-stream","not a backup".getBytes());
        mvc.perform(multipart("/api/backups").file(fake)).andExpect(status().isUnauthorized());
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        mvc.perform(multipart("/api/backups").file(fake).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_archives",Integer.class)).isZero();
    }
    @Test void invalidArchiveIsRejectedWithoutChangingTheDatabase() throws Exception {
        var fake=new MockMultipartFile("file","fake.dump","application/octet-stream","not a backup".getBytes());
        mvc.perform(multipart("/api/backups").file(fake).header("Authorization","Bearer "+token))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("BACKUP_INVALID"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_archives",Integer.class)).isZero();
    }
    @Test void exportAndUploadStageArchivesWithoutRestoringLiveDatabase() throws Exception {
        Assumptions.assumeTrue(toolAvailable("pg_dump") && toolAvailable("pg_restore"));
        var created=json.readTree(mvc.perform(post("/api/backups/exports").header("Authorization","Bearer "+token))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID exportId=UUID.fromString(created.path("id").asText());
        Path archive=STAGED.resolve(exportId+".dump");
        assertThat(Files.readAllBytes(archive)).startsWith("PGDMP".getBytes());
        var upload=new MockMultipartFile("file","copy.dump","application/octet-stream",Files.readAllBytes(archive));
        mvc.perform(multipart("/api/backups").file(upload).param("expectedSha256","0".repeat(64))
            .header("Authorization","Bearer "+token))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("BACKUP_CHECKSUM_INVALID"));
        mvc.perform(multipart("/api/backups").file(upload).param("expectedSha256",created.path("sha256").asText())
            .header("Authorization","Bearer "+token))
            .andExpect(status().isCreated()).andExpect(jsonPath("source").value("UPLOAD"));
        mvc.perform(get("/api/backups/"+exportId+"/checksum").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(content().string(created.path("sha256").asText()+"  alanan-"+exportId+".dump\n"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_archives",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_events",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/backups").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
    private static boolean toolAvailable(String name) {
        try { return new ProcessBuilder(name,"--version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()==0; }
        catch(Exception ex) { return false; }
    }
}
