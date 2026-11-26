package com.example.comiclab;

import android.os.Environment;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEnvironment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, shadows = ComicLabStorageStartupTest.StorageShadow.class)
public class ComicLabStorageStartupTest {
    @Test
    public void startupRecoveryDoesNotResolveTheExternalStorageRootFromItsWorker() throws Exception {
        RuntimeEnvironment.getApplication();

        assertTrue("NAS recovery must still run from the worker",
                StorageShadow.recoveryStarted.await(5, TimeUnit.SECONDS));
        assertFalse("The worker must use the root resolved during application startup",
                StorageShadow.rootResolvedByWorker.get());
    }

    @Implements(Environment.class)
    public static class StorageShadow extends ShadowEnvironment {
        private static final CountDownLatch recoveryStarted = new CountDownLatch(1);
        private static final AtomicBoolean rootResolvedByWorker = new AtomicBoolean();

        @Implementation
        protected static File getExternalStorageDirectory() {
            if (inStartupWorker()) {
                rootResolvedByWorker.set(true);
            }
            return ShadowEnvironment.getExternalStorageDirectory();
        }

        @Implementation
        protected static boolean isExternalStorageManager() {
            if (inStartupWorker()) {
                recoveryStarted.countDown();
            }
            return false;
        }

        private static boolean inStartupWorker() {
            return Thread.currentThread().getName().equals("archive-cache-cleanup");
        }
    }
}
