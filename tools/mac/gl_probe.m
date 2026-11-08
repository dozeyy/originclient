// What this Mac (a CI runner) can draw with, independent of Minecraft and of Origin: mac-launcher.yml compiles and runs
// it before "Game starts" so a game that can't open its window is told apart from a machine that can't open ANY OpenGL
// window. Prints one line per question; always exits 0 (it only reports).
//   accelerated GL   an accelerated (GPU) OpenGL pixel format, what LWJGL 2 (1.8.9) and GLFW (LWJGL 3) ask for
//   software GL      Apple's software renderer
//   window           an ordinary NSWindow from this process shows up in the window server's on-screen list
// Build: clang -fobjc-arc -framework Cocoa -framework OpenGL -o gl_probe tools/mac/gl_probe.m
#define GL_SILENCE_DEPRECATION
#import <Cocoa/Cocoa.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl.h>
#include <unistd.h>

static void pixelFormat(const char *name, CGLPixelFormatAttribute *attrs) {
    CGLPixelFormatObj pix = NULL;
    GLint count = 0;
    CGLError err = CGLChoosePixelFormat(attrs, &pix, &count);
    if (err != kCGLNoError || pix == NULL) {
        printf("%-17s no  (%s)\n", name, CGLErrorString(err));
        return;
    }
    CGLContextObj ctx = NULL;
    err = CGLCreateContext(pix, NULL, &ctx);
    const char *renderer = "?";
    if (err == kCGLNoError && ctx != NULL) {
        CGLSetCurrentContext(ctx);
        const GLubyte *r = glGetString(GL_RENDERER);
        if (r) renderer = (const char *)r;
    }
    printf("%-17s yes (%d virtual screen(s), renderer %s)\n", name, count, renderer);
    if (ctx) { CGLSetCurrentContext(NULL); CGLDestroyContext(ctx); }
    CGLDestroyPixelFormat(pix);
}

int main(void) {
    @autoreleasepool {
        CGLPixelFormatAttribute accelerated[] = { kCGLPFAAccelerated, kCGLPFAColorSize, 24, kCGLPFADepthSize, 16, 0 };
        CGLPixelFormatAttribute software[] = { kCGLPFARendererID, (CGLPixelFormatAttribute)kCGLRendererGenericFloatID, 0 };
        pixelFormat("accelerated GL", accelerated);
        pixelFormat("software GL", software);

        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(100, 100, 320, 240)
                                                       styleMask:NSWindowStyleMaskTitled
                                                         backing:NSBackingStoreBuffered
                                                           defer:NO];
        [window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
        BOOL seen = NO;
        for (int i = 0; i < 30 && !seen; i++) {
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
            CFArrayRef list = CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements, kCGNullWindowID);
            for (CFIndex k = 0; list && k < CFArrayGetCount(list); k++) {
                CFDictionaryRef info = CFArrayGetValueAtIndex(list, k);
                CFNumberRef owner = CFDictionaryGetValue(info, kCGWindowOwnerPID);
                int pid = 0;
                if (owner && CFNumberGetValue(owner, kCFNumberIntType, &pid) && pid == getpid()) seen = YES;
            }
            if (list) CFRelease(list);
        }
        printf("%-17s %s\n", "window", seen ? "yes (on screen)" : "no  (never on screen)");
    }
    return 0;
}
