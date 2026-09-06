package com.salkcoding.oswl.service.snapshot;

import com.salkcoding.oswl.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.Set;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="OSWL_VERIFY_MAX_SNAPSHOT", matches="true")
class SnapshotStagingBoundaryTest {
    @TempDir Path temporary;
    @Test void acceptsExactExpandedBudgetAndRejectsOneByteMoreWithBoundedHeap() throws Exception {
        for(int extra=0;extra<=1;extra++) {
            Path archive=temporary.resolve("boundary-"+extra+".zip");
            byte[] block=new byte[65536];
            long[] sizes={200L*1024*1024,200L*1024*1024,112L*1024*1024-2+extra};
            try(var zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))) {
                zip.putNextEntry(new ZipEntry("meta.json"));zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                for(int i=0;i<sizes.length;i++) {
                    var crc=new CRC32();
                    for(long remaining=sizes[i];remaining>0;remaining-=Math.min(remaining,block.length)) crc.update(block,0,(int)Math.min(remaining,block.length));
                    var entry=new ZipEntry("unknown-"+i);entry.setMethod(ZipEntry.STORED);entry.setSize(sizes[i]);entry.setCompressedSize(sizes[i]);entry.setCrc(crc.getValue());zip.putNextEntry(entry);
                    for(long remaining=sizes[i];remaining>0;remaining-=Math.min(remaining,block.length)) zip.write(block,0,(int)Math.min(remaining,block.length));
                    zip.closeEntry();
                }
            }
            var stage=new SnapshotBundleStager();
            try(stage;var input=Files.newInputStream(archive)) {
                if(extra==0) {stage.read(input,Set.of());assertThat(stage.files()).containsKey("meta.json");}
                else assertThatThrownBy(()->stage.read(input,Set.of())).isInstanceOf(InvalidRequestException.class).hasMessageContaining("decompressed size limit");
            }
            assertThat(stage.files().values()).allMatch(path->!Files.exists(path));
            Files.delete(archive);
        }
        System.out.println("Snapshot expanded boundary: 512 MiB accepted, +1 byte rejected; staged files removed; JVM max heap="+Runtime.getRuntime().maxMemory());
    }
}
