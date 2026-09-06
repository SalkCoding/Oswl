package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.enums.Reachability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static com.salkcoding.oswl.service.reachability.SourceReferenceAnalyzer.Language.*;

class SourceReferenceAnalyzerTest {
    @TempDir Path root;
    private final SourceReferenceAnalyzer analyzer = new SourceReferenceAnalyzer();

    @Test void separatesLanguagesAndDoesNotInferSafetyFromAbsence() throws Exception {
        Files.writeString(root.resolve("app.py"), "import same # used\nfrom requests import get\n");
        Files.writeString(root.resolve("app.js"), "import other from 'other'; const lazy = import(name);");
        var index = analyzer.index(root);
        assertThat(index.match(PYTHON, Set.of("same")).reachability()).isEqualTo(Reachability.REACHABLE);
        assertThat(index.match(JAVASCRIPT, Set.of("same")).reachability()).isEqualTo(Reachability.UNKNOWN);
        assertThat(index.match(PYTHON, Set.of("missing")).reachability()).isEqualTo(Reachability.UNKNOWN);
        assertThat(index.match(JAVASCRIPT, Set.of("other")).reachability()).isEqualTo(Reachability.REACHABLE);
    }

    @Test void ignoresCommentsStringsAndPrunesExcludedDirectories() throws Exception {
        Files.writeString(root.resolve("app.py"), "# import fake\ns = \"import fake\"\nimport real\n");
        Files.writeString(root.resolve("app.js"), "// require('fake')\nconst s = \"require('fake')\"; require('real'); export {x} from '@scope/pkg/sub';");
        Files.createDirectories(root.resolve("node_modules/hidden"));
        Files.writeString(root.resolve("node_modules/hidden/app.py"), "import fake");
        var index = analyzer.index(root);
        assertThat(index.files()).containsEntry(PYTHON, 1).containsEntry(JAVASCRIPT, 1);
        assertThat(index.match(PYTHON, Set.of("fake")).reachability()).isEqualTo(Reachability.UNKNOWN);
        assertThat(index.match(JAVASCRIPT, Set.of("fake")).reachability()).isEqualTo(Reachability.UNKNOWN);
        assertThat(index.match(JAVASCRIPT, Set.of("@scope/pkg")).reachability()).isEqualTo(Reachability.REACHABLE);
    }

    @Test void recordsCoverageLimitsAndInvalidUtf8() throws Exception {
        Files.writeString(root.resolve("large.py"), "x".repeat(2 * 1024 * 1024 + 1));
        assertThat(analyzer.index(root).limitations()).contains("OVERSIZE_FILE");
        Files.write(root.resolve("broken.py"), new byte[]{(byte) 0xc3, 0x28});
        assertThat(analyzer.index(root).limitations()).contains("UNREADABLE_FILE");
        assertThat(analyzer.index(root, 0, 100, 1000).limitations()).contains("FILE_LIMIT");
        assertThat(analyzer.index(root, 100, 0, 1000).limitations()).contains("BYTE_LIMIT");
        assertThat(analyzer.index(root, 100, 100, 0).limitations()).contains("TIME_LIMIT");
    }
}
