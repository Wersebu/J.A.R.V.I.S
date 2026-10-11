package com.jarvis.core.files;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedFilesTest {

    @Test
    void filesBelongToOneUserAndExpire() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-11T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        SharedFiles files = new SharedFiles(clock);
        SharedFiles.SharedFile file = files.share("owner", "polisa.pdf", "application/pdf", new byte[] {1, 2, 3});

        assertThat(file.id()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(files.find(file.id(), "owner")).isPresent();
        assertThat(files.find(file.id(), "mom")).isEmpty();
        now.set(now.get().plus(SharedFiles.TIME_TO_LIVE));
        assertThat(files.find(file.id(), "owner")).isEmpty();
        assertThatThrownBy(() -> files.share("", "x.pdf", "application/pdf", new byte[1])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namesCannotEscapeTheDownloadFolder() {
        assertThat(SharedFiles.safeName("..\\..\\Windows\\evil.exe")).isEqualTo("evil.exe");
        assertThat(SharedFiles.safeName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(SharedFiles.safeName("faktura:10/2026?.pdf")).isEqualTo("2026_.pdf");
        assertThat(SharedFiles.safeName(".hidden.pdf")).isEqualTo("hidden.pdf");
        assertThat(SharedFiles.safeName("")).isEqualTo("plik");
        assertThat(SharedFiles.safeName("a".repeat(300) + ".pdf")).hasSize(150).endsWith(".pdf");
    }

    @Test
    void oldestFilesMakeRoomWhenMemoryIsFull() {
        SharedFiles files = new SharedFiles();
        int half = (int) (SharedFiles.MAX_TOTAL_BYTES / 2) + 1;
        SharedFiles.SharedFile first = files.share("owner", "a.pdf", "application/pdf", new byte[half]);
        SharedFiles.SharedFile second = files.share("owner", "b.pdf", "application/pdf", new byte[half]);
        assertThat(files.find(first.id(), "owner")).isEmpty();
        assertThat(files.find(second.id(), "owner")).isPresent();
    }
}
