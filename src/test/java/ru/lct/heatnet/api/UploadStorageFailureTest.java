package ru.lct.heatnet.api;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.FileSystemException;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadStorageFailureTest {
    @Test void diskFullFromNativeChannelAndFilesystemOperationsIs507ButNetworkFailureIsRetryable503() {
        UploadController controller = new UploadController(null);
        // FileChannel's native write reports ENOSPC as IOException, not necessarily FileSystemException.
        assertEquals(507, controller.storageFailure(new IOException("No space left on device")).getStatusCodeValue());
        assertEquals(507, controller.storageFailure(new FileSystemException("data", null, "No space left on device")).getStatusCodeValue());
        assertEquals(507, controller.storageFailure(new IOException("Disk quota exceeded")).getStatusCodeValue());
        assertEquals(503, controller.storageFailure(new IOException("Connection reset")).getStatusCodeValue());
    }
}
