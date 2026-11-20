#include <jni.h>
#include <cerrno>
#include <fcntl.h>
#include <stdio.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_example_comiclab_core_transfer_TransferFileMove_moveNoReplace(
    JNIEnv* env, jobject, jbyteArray source, jbyteArray target) {
    jbyte* source_path = env->GetByteArrayElements(source, nullptr);
    if (!source_path) return 3;
    jbyte* target_path = env->GetByteArrayElements(target, nullptr);
    if (!target_path) {
        env->ReleaseByteArrayElements(source, source_path, JNI_ABORT);
        return 3;
    }

    const int result = renameat2(AT_FDCWD, reinterpret_cast<const char*>(source_path),
                                AT_FDCWD, reinterpret_cast<const char*>(target_path),
                                RENAME_NOREPLACE);
    const int error = result == 0 ? 0 : errno;
    env->ReleaseByteArrayElements(target, target_path, JNI_ABORT);
    env->ReleaseByteArrayElements(source, source_path, JNI_ABORT);

    switch (error) {
        case 0:
            return 0;
        case EEXIST:
        case ENOTEMPTY:
            return 1;
        case EXDEV:
        case ENOSYS:
        case EINVAL:
        case EOPNOTSUPP:
        case EACCES:
        case EPERM:
        case EROFS:
            return 2;
        default:
            return 3;
    }
}
