#include <Security/Security.h>
// Diagnostic SPI used only by the isolated regression executable, never shipped.
// Public SecTrustedApplicationCopyData returns only a path, not code validity.
OSStatus SecTrustedApplicationValidateWithPath(SecTrustedApplicationRef app, const char *path);
