package ci.itechciv.sigdep.sync.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Le répertoire du buffer contient des données de santé nominatives : il doit
 * être resserré à son seul propriétaire ({@code rwx------}), y compris quand il
 * préexistait avec des permissions trop ouvertes (base historique installée
 * avant ce durcissement). Test POSIX uniquement — sur Windows le contrôle
 * d'accès est posé par le packaging (icacls).
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class BufferDirectoryPermissionsTest {

    @Test
    @DisplayName("Un répertoire buffer 755 préexistant est resserré en 700")
    void looseDirectory_isTightenedTo700(@TempDir Path tmp) throws IOException {
        Path buffer = tmp.resolve("buffer-dir");
        Files.createDirectory(buffer);
        Files.setPosixFilePermissions(buffer,
                PosixFilePermissions.fromString("rwxr-xr-x")); // 755, lisible par tous

        BufferSchemaInitializer.restrictBufferDirectoryPermissions(buffer);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(buffer);
        assertEquals(PosixFilePermissions.fromString("rwx------"), perms,
                "le groupe et les autres ne doivent plus avoir aucun accès");
    }

    @Test
    @DisplayName("Aucun accès résiduel pour groupe/autres après resserrement")
    void noResidualGroupOrOtherAccess(@TempDir Path tmp) throws IOException {
        Path buffer = tmp.resolve("buffer-dir");
        Files.createDirectory(buffer);

        BufferSchemaInitializer.restrictBufferDirectoryPermissions(buffer);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(buffer);
        for (PosixFilePermission p : PosixFilePermission.values()) {
            boolean ownerPerm = p.name().startsWith("OWNER");
            assertEquals(ownerPerm, perms.contains(p),
                    "seules les permissions OWNER doivent rester : " + p);
        }
    }

    @Test
    @DisplayName("La vue POSIX est bien disponible sur cet OS (sanity)")
    void posixSupported() {
        assertEquals(true,
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }
}
