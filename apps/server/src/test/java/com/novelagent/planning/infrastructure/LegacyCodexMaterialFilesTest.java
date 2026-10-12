package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.platform.support.Sha256;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyCodexMaterialFilesTest {
    @Test void fullUtf8SourceIsSavedIdempotentlyAndInvalidIdentityIsRejected(@TempDir Path temp) throws Exception {
        var client = new CodexAppServerClient(new ObjectMapper(), "unused", "model", "high", 10,
                temp.resolve("runtime").toString(), temp.resolve("unused-auth").toString(), "");
        var project = UUID.randomUUID();
        String source = "首行\n完整原文、引号\"和换行\r\n最后一行";
        String id = Sha256.ofUtf8(source);
        Path target = temp.resolve("runtime").resolve(project.toString()).resolve(".novel-materials").resolve(id + ".txt");
        client.saveMaterialFiles(project, Map.of(id, source));
        assertThat(Files.readString(target)).isEqualTo(source);
        var modified = Files.getLastModifiedTime(target);
        client.saveMaterialFiles(project, Map.of(id, source));
        assertThat(Files.getLastModifiedTime(target)).isEqualTo(modified);
        assertThatThrownBy(() -> client.saveMaterialFiles(project, Map.of("../escape", source)))
                .isInstanceOf(CodexAppServerException.class);
        assertThatThrownBy(() -> client.saveMaterialFiles(project, Map.of(id, "changed")))
                .isInstanceOf(CodexAppServerException.class);
        assertThat(Files.readString(target)).isEqualTo(source);
    }
}
