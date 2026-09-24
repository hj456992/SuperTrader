#import <AppKit/AppKit.h>
#import <WebKit/WebKit.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import "CLNativeImport.h"

static NSString *const CLDisplayTarget = @"卧底不追高";

@interface CLDropWebView : WKWebView <NSDraggingDestination>
@property (nonatomic, copy) void (^zipDropHandler)(NSArray<NSURL *> *urls);
@end

@implementation CLDropWebView
- (instancetype)initWithFrame:(NSRect)frame configuration:(WKWebViewConfiguration *)configuration {
    self = [super initWithFrame:frame configuration:configuration];
    if (self) [self registerForDraggedTypes:@[NSPasteboardTypeFileURL]];
    return self;
}
- (NSDragOperation)draggingEntered:(id<NSDraggingInfo>)sender {
    NSArray *urls = [sender.draggingPasteboard readObjectsForClasses:@[NSURL.class]
                                                              options:@{NSPasteboardURLReadingFileURLsOnlyKey: @YES}];
    for (NSURL *url in urls) if ([url.pathExtension.lowercaseString isEqualToString:@"zip"]) return NSDragOperationCopy;
    return NSDragOperationNone;
}
- (BOOL)performDragOperation:(id<NSDraggingInfo>)sender {
    NSArray *urls = [sender.draggingPasteboard readObjectsForClasses:@[NSURL.class]
                                                              options:@{NSPasteboardURLReadingFileURLsOnlyKey: @YES}];
    NSPredicate *zipOnly = [NSPredicate predicateWithBlock:^BOOL(NSURL *url, NSDictionary *bindings) {
        return [url.pathExtension.lowercaseString isEqualToString:@"zip"];
    }];
    NSArray *zipURLs = [urls filteredArrayUsingPredicate:zipOnly];
    if (zipURLs.count && self.zipDropHandler) self.zipDropHandler(zipURLs);
    return zipURLs.count > 0;
}
@end

@interface CLAppDelegate : NSObject <NSApplicationDelegate, WKUIDelegate, WKNavigationDelegate, WKDownloadDelegate>
@property (nonatomic, strong) NSWindow *window;
@property (nonatomic, strong) CLDropWebView *webView;
@property (nonatomic, strong) NSTextField *targetLabel;
@property (nonatomic, copy) NSDictionary *config;
@property (nonatomic, strong) NSTask *serviceTask;
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *pendingURLs;
@property (nonatomic) BOOL serviceReady;
@property (nonatomic) BOOL scannedExtensionInbox;
@property (nonatomic, strong) NSMutableSet<WKDownload *> *activeDownloads;
@end

@implementation CLAppDelegate
- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    if (!self.pendingURLs) self.pendingURLs = NSMutableArray.array;
    self.activeDownloads = NSMutableSet.set;
    [self buildWindow];
    NSError *error = nil;
    self.config = CLLoadNativeConfig(NSBundle.mainBundle, &error);
    if (!self.config) {
        [self presentError:error];
        return;
    }
    [self connectOrStartService];
}

- (void)buildWindow {
    NSRect frame = NSMakeRect(0, 0, 1120, 760);
    self.window = [[NSWindow alloc] initWithContentRect:frame
                                              styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable
                                                backing:NSBackingStoreBuffered
                                                  defer:NO];
    self.window.title = @"聊天日志";
    self.window.minSize = NSMakeSize(760, 520);
    [self.window center];

    NSView *content = [[NSView alloc] initWithFrame:frame];
    content.autoresizesSubviews = YES;
    self.window.contentView = content;

    NSVisualEffectView *banner = [[NSVisualEffectView alloc] initWithFrame:NSMakeRect(0, frame.size.height - 48, frame.size.width, 48)];
    banner.material = NSVisualEffectMaterialHeaderView;
    banner.blendingMode = NSVisualEffectBlendingModeWithinWindow;
    banner.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
    [content addSubview:banner];

    self.targetLabel = [NSTextField labelWithString:[NSString stringWithFormat:@"自动同步：%@", CLDisplayTarget]];
    self.targetLabel.frame = NSMakeRect(18, 14, frame.size.width - 180, 20);
    self.targetLabel.font = [NSFont systemFontOfSize:13 weight:NSFontWeightSemibold];
    self.targetLabel.autoresizingMask = NSViewWidthSizable;
    [banner addSubview:self.targetLabel];

    NSButton *chooseButton = [NSButton buttonWithTitle:@"选择 ZIP…" target:self action:@selector(chooseZIP:)];
    chooseButton.frame = NSMakeRect(frame.size.width - 130, 9, 112, 30);
    chooseButton.autoresizingMask = NSViewMinXMargin;
    [banner addSubview:chooseButton];

    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    self.webView = [[CLDropWebView alloc] initWithFrame:NSMakeRect(0, 0, frame.size.width, frame.size.height - 48)
                                           configuration:configuration];
    self.webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    self.webView.UIDelegate = self;
    self.webView.navigationDelegate = self;
    __weak typeof(self) weakSelf = self;
    self.webView.zipDropHandler = ^(NSArray<NSURL *> *urls) { [weakSelf queueZIPURLs:urls]; };
    [content addSubview:self.webView];

    [self.window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
}

- (void)chooseZIP:(id)sender {
    NSOpenPanel *panel = NSOpenPanel.openPanel;
    panel.title = [NSString stringWithFormat:@"导入到%@日志", CLDisplayTarget];
    panel.message = @"选择微信原生导出的 ZIP 消息包。";
    panel.allowedContentTypes = @[UTTypeZIP];
    panel.allowsMultipleSelection = YES;
    panel.canChooseDirectories = NO;
    [panel beginSheetModalForWindow:self.window completionHandler:^(NSModalResponse result) {
        if (result == NSModalResponseOK) [self queueZIPURLs:panel.URLs];
    }];
}

- (void)application:(NSApplication *)application openURLs:(NSArray<NSURL *> *)urls {
    [self queueZIPURLs:urls];
}

- (void)queueZIPURLs:(NSArray<NSURL *> *)urls {
    if (!urls.count) return;
    if (!self.pendingURLs) self.pendingURLs = NSMutableArray.array;
    for (NSURL *url in urls) [self.pendingURLs addObject:@{@"url": url, @"pending": @NO}];
    if (self.serviceReady) [self importNextURL];
}

- (void)webView:(WKWebView *)webView
runOpenPanelWithParameters:(WKOpenPanelParameters *)parameters
initiatedByFrame:(WKFrameInfo *)frame
completionHandler:(void (^)(NSArray<NSURL *> * _Nullable URLs))completionHandler {
    NSOpenPanel *panel = NSOpenPanel.openPanel;
    panel.title = [NSString stringWithFormat:@"导入到%@日志", CLDisplayTarget];
    panel.message = @"选择微信原生导出的 ZIP 消息包。";
    panel.allowedContentTypes = @[UTTypeZIP];
    panel.allowsMultipleSelection = parameters.allowsMultipleSelection;
    panel.canChooseDirectories = NO;
    [panel beginSheetModalForWindow:self.window completionHandler:^(NSModalResponse result) {
        completionHandler(result == NSModalResponseOK ? panel.URLs : nil);
    }];
}

- (void)webView:(WKWebView *)webView
decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    NSURL *url = navigationAction.request.URL;
    if (CLIsAttachmentURL(url)) {
        decisionHandler(navigationAction.navigationType == WKNavigationTypeLinkActivated
            ? WKNavigationActionPolicyDownload
            : WKNavigationActionPolicyCancel);
        return;
    }
    decisionHandler(WKNavigationActionPolicyAllow);
}

- (void)webView:(WKWebView *)webView
decidePolicyForNavigationResponse:(WKNavigationResponse *)navigationResponse
decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler {
    decisionHandler(CLIsAttachmentURL(navigationResponse.response.URL)
        ? WKNavigationResponsePolicyDownload
        : WKNavigationResponsePolicyAllow);
}

- (void)webView:(WKWebView *)webView
navigationAction:(WKNavigationAction *)navigationAction
didBecomeDownload:(WKDownload *)download {
    [self beginDownload:download];
}

- (void)webView:(WKWebView *)webView
navigationResponse:(WKNavigationResponse *)navigationResponse
didBecomeDownload:(WKDownload *)download {
    [self beginDownload:download];
}

- (void)beginDownload:(WKDownload *)download {
    [self.activeDownloads addObject:download];
    download.delegate = self;
}

- (void)download:(WKDownload *)download
decideDestinationUsingResponse:(NSURLResponse *)response
suggestedFilename:(NSString *)suggestedFilename
completionHandler:(void (^)(NSURL * _Nullable destination))completionHandler {
    NSSavePanel *panel = NSSavePanel.savePanel;
    panel.title = @"保存聊天日志附件";
    panel.nameFieldStringValue = suggestedFilename.length ? suggestedFilename : @"附件";
    panel.canCreateDirectories = YES;
    [panel beginSheetModalForWindow:self.window completionHandler:^(NSModalResponse result) {
        if (result != NSModalResponseOK || !panel.URL) {
            [self.activeDownloads removeObject:download];
            completionHandler(nil);
            return;
        }
        NSError *destinationError = nil;
        if (!CLDownloadDestinationIsSafe(panel.URL, &destinationError)) {
            [self.activeDownloads removeObject:download];
            completionHandler(nil);
            [self presentError:destinationError];
            return;
        }
        completionHandler(panel.URL);
    }];
}

- (void)download:(WKDownload *)download
willPerformHTTPRedirection:(NSHTTPURLResponse *)response
newRequest:(NSURLRequest *)request
decisionHandler:(void (^)(WKDownloadRedirectPolicy))decisionHandler {
    decisionHandler(WKDownloadRedirectPolicyCancel);
}

- (void)downloadDidFinish:(WKDownload *)download {
    [self.activeDownloads removeObject:download];
    self.targetLabel.stringValue = [NSString stringWithFormat:@"附件已保存 · 导入目标：%@（由你指定）", CLDisplayTarget];
}

- (void)download:(WKDownload *)download didFailWithError:(NSError *)error resumeData:(NSData *)resumeData {
    [self.activeDownloads removeObject:download];
    if ([error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled) return;
    [self presentError:error];
}

- (void)connectOrStartService {
    if ([self.config[@"server"] lastPathComponent] && [[self.config[@"server"] lastPathComponent] isEqualToString:@"launch.py"]) {
        [self launchService];
        return;
    }
    __weak typeof(self) weakSelf = self;
    CLProbeHealth(self.config, ^(BOOL healthy, NSError *error) {
        if (healthy) {
            [weakSelf serviceBecameReady];
        } else {
            [weakSelf launchService];
        }
    });
}

- (void)launchService {
    NSError *launchInfoError = nil;
    NSDictionary *launchInfo = CLServiceLaunchInfo(self.config, &launchInfoError);
    if (!launchInfo) {
        [self presentError:launchInfoError];
        return;
    }
    NSString *python = launchInfo[@"executable"];
    NSString *server = [launchInfo[@"arguments"] firstObject];
    if (![[NSFileManager defaultManager] isExecutableFileAtPath:python] ||
        ![[NSFileManager defaultManager] isReadableFileAtPath:server]) {
        [self presentError:[NSError errorWithDomain:CLNativeImportErrorDomain code:CLNativeImportErrorInvalidConfig
                                           userInfo:@{NSLocalizedDescriptionKey: @"找不到日志服务程序或 Python。"}]];
        return;
    }
    NSTask *task = [[NSTask alloc] init];
    task.executableURL = [NSURL fileURLWithPath:python];
    task.arguments = launchInfo[@"arguments"];
    task.currentDirectoryURL = [NSURL fileURLWithPath:launchInfo[@"cwd"] isDirectory:YES];
    NSFileHandle *nullHandle = [NSFileHandle fileHandleForWritingAtPath:@"/dev/null"];
    task.standardInput = [NSFileHandle fileHandleForReadingAtPath:@"/dev/null"];
    task.standardOutput = nullHandle;
    task.standardError = nullHandle;
    NSError *launchError = nil;
    if (![task launchAndReturnError:&launchError]) {
        [self presentError:launchError];
        return;
    }
    self.serviceTask = task;
    [self pollForServiceAttempt:0];
}

- (void)pollForServiceAttempt:(NSUInteger)attempt {
    if (attempt >= 40) {
        [self presentError:[NSError errorWithDomain:CLNativeImportErrorDomain code:CLNativeImportErrorInvalidResponse
                                           userInfo:@{NSLocalizedDescriptionKey: @"日志服务未能在 10 秒内启动。"}]];
        return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.25 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        CLProbeHealth(self.config, ^(BOOL healthy, NSError *error) {
            if (healthy) [self serviceBecameReady];
            else [self pollForServiceAttempt:attempt + 1];
        });
    });
}

- (void)serviceBecameReady {
    if (!self.serviceReady) {
        self.serviceReady = YES;
        [self loadWebUI];
    }
    if (!self.scannedExtensionInbox) {
        self.scannedExtensionInbox = YES;
        for (NSURL *url in CLPendingZIPs(CLShareExtensionPendingInboxURL())) {
            [self.pendingURLs addObject:@{@"url": url, @"pending": @YES}];
        }
    }
    [self importNextURL];
}

- (void)loadWebUI {
    NSURL *url = [NSURL URLWithString:self.config[@"url"]];
    [self.webView loadRequest:[NSURLRequest requestWithURL:url cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:10.0]];
}

- (void)importNextURL {
    if (!self.serviceReady || self.pendingURLs.count == 0 || [self.targetLabel.stringValue hasPrefix:@"正在导入"]) return;
    NSDictionary *entry = self.pendingURLs.firstObject;
    NSURL *url = entry[@"url"];
    BOOL pending = [entry[@"pending"] boolValue];
    [self.pendingURLs removeObjectAtIndex:0];
    self.targetLabel.stringValue = [NSString stringWithFormat:@"正在导入到%@：%@", CLDisplayTarget, url.lastPathComponent];
    __weak typeof(self) weakSelf = self;
    CLSendZIP(url, self.config, ^(NSDictionary *result, NSError *error) {
        if (error) {
            weakSelf.targetLabel.stringValue = [NSString stringWithFormat:@"自动同步：%@", CLDisplayTarget];
            [weakSelf presentError:error];
        } else {
            NSInteger added = [result[@"added"] integerValue];
            BOOL duplicate = [result[@"duplicate"] boolValue];
            weakSelf.targetLabel.stringValue = duplicate
                ? [NSString stringWithFormat:@"%@：该消息包已导入", CLDisplayTarget]
                : [NSString stringWithFormat:@"%@：已新增 %ld 条", CLDisplayTarget, (long)added];
            [weakSelf loadWebUI];
            if (pending) CLRemovePendingZIP(url);
        }
        [weakSelf importNextURL];
    });
}

- (void)presentError:(NSError *)error {
    NSAlert *alert = [[NSAlert alloc] init];
    alert.alertStyle = NSAlertStyleWarning;
    alert.messageText = @"聊天日志未完成操作";
    alert.informativeText = error.localizedDescription ?: @"发生未知错误。";
    if (self.window) [alert beginSheetModalForWindow:self.window completionHandler:nil];
    else [alert runModal];
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)sender { return YES; }
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)flag {
    [self.window makeKeyAndOrderFront:nil];
    return YES;
}
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSApplication *application = NSApplication.sharedApplication;
        application.activationPolicy = NSApplicationActivationPolicyRegular;
        CLAppDelegate *delegate = [[CLAppDelegate alloc] init];
        application.delegate = delegate;
        [application run];
    }
    return 0;
}
