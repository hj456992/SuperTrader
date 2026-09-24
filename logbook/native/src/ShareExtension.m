#import <AppKit/AppKit.h>
#import "CLNativeImport.h"

extern int NSExtensionMain(int argc, const char *argv[]);

@interface CLShareViewController : NSViewController
@property (nonatomic, strong) NSTextField *statusLabel;
@property (nonatomic, copy) NSDictionary *config;
@property (nonatomic) BOOL started;
@end

@implementation CLShareViewController
- (void)loadView {
    self.view = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 420, 142)];
    NSTextField *title = [NSTextField labelWithString:@"存入卧底不追高日志"];
    title.frame = NSMakeRect(24, 98, 372, 24);
    title.font = [NSFont systemFontOfSize:17 weight:NSFontWeightSemibold];
    [self.view addSubview:title];
    self.statusLabel = [NSTextField wrappingLabelWithString:@"导入目标：卧底不追高（由你指定）"];
    self.statusLabel.frame = NSMakeRect(24, 42, 372, 44);
    self.statusLabel.maximumNumberOfLines = 2;
    [self.view addSubview:self.statusLabel];
    self.preferredContentSize = NSMakeSize(420, 142);
}

- (void)viewDidLoad {
    [super viewDidLoad];
    if (self.started) return;
    self.started = YES;
    NSError *error = nil;
    self.config = CLLoadNativeConfig(NSBundle.mainBundle, &error);
    if (!self.config) {
        [self fail:error];
        return;
    }
    NSItemProvider *provider = [self firstZIPProvider];
    if (!provider) {
        [self fail:[NSError errorWithDomain:CLNativeImportErrorDomain code:CLNativeImportErrorWrongType
                                   userInfo:@{NSLocalizedDescriptionKey: @"共享内容中没有 ZIP 文件。"}]];
        return;
    }
    NSString *type = [provider hasItemConformingToTypeIdentifier:@"public.zip-archive"] ? @"public.zip-archive" : @"public.file-url";
    self.statusLabel.stringValue = @"正在读取微信导出的 ZIP…";
    [provider loadItemForTypeIdentifier:type options:nil completionHandler:^(id<NSSecureCoding> item, NSError *loadError) {
        dispatch_async(dispatch_get_main_queue(), ^{
            id object = item;
            NSURL *url = [object isKindOfClass:NSURL.class] ? (NSURL *)object : nil;
            if (!url && [object isKindOfClass:NSData.class]) {
                NSString *text = [[NSString alloc] initWithData:(NSData *)item encoding:NSUTF8StringEncoding];
                if (text.length) url = [NSURL URLWithString:text];
            }
            if (loadError || !url) {
                [self fail:loadError ?: [NSError errorWithDomain:CLNativeImportErrorDomain code:CLNativeImportErrorNotFileURL
                                                        userInfo:@{NSLocalizedDescriptionKey: @"无法取得共享的 ZIP 文件地址。"}]];
                return;
            }
            [self importURL:url];
        });
    }];
}

- (NSItemProvider *)firstZIPProvider {
    for (id rawItem in self.extensionContext.inputItems) {
        if (![rawItem isKindOfClass:NSExtensionItem.class]) continue;
        for (NSItemProvider *provider in [(NSExtensionItem *)rawItem attachments]) {
            if ([provider hasItemConformingToTypeIdentifier:@"public.zip-archive"] ||
                [provider hasItemConformingToTypeIdentifier:@"public.file-url"]) return provider;
        }
    }
    return nil;
}

- (void)importURL:(NSURL *)url {
    self.statusLabel.stringValue = @"正在存入卧底不追高日志…";
    CLProbeHealth(self.config, ^(BOOL healthy, NSError *healthError) {
        if (!healthy) {
            [self preserveURL:url reason:healthError];
            return;
        }
        CLSendZIP(url, self.config, ^(NSDictionary *result, NSError *sendError) {
            if (sendError && [sendError.domain isEqualToString:NSURLErrorDomain]) {
                [self preserveURL:url reason:sendError];
            } else if (sendError) {
                [self fail:sendError];
            } else {
                NSInteger added = [result[@"added"] integerValue];
                self.statusLabel.stringValue = [result[@"duplicate"] boolValue]
                    ? @"已在卧底不追高日志中，无需重复导入。"
                    : [NSString stringWithFormat:@"已存入卧底不追高日志：新增 %ld 条。", (long)added];
                [self finishAfterDelay:0.7];
            }
        });
    });
}

- (void)preserveURL:(NSURL *)url reason:(NSError *)reason {
    NSError *saveError = nil;
    BOOL accessed = [url startAccessingSecurityScopedResource];
    NSURL *saved = CLPreservePendingZIP(url, CLDefaultPendingInboxURL(), @"卧底不追高", &saveError);
    if (accessed) [url stopAccessingSecurityScopedResource];
    if (!saved) {
        [self fail:saveError ?: reason];
        return;
    }
    self.statusLabel.stringValue = @"日志服务未连接；ZIP 已安全暂存。请退出后重新打开聊天日志以重试。";
    [self finishAfterDelay:1.4];
}

- (void)finishAfterDelay:(NSTimeInterval)delay {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [self.extensionContext completeRequestReturningItems:nil completionHandler:nil];
    });
}

- (void)fail:(NSError *)error {
    self.statusLabel.stringValue = error.localizedDescription ?: @"未能导入这个 ZIP 文件。";
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.4 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [self.extensionContext cancelRequestWithError:error];
    });
}
@end

int main(int argc, const char *argv[]) {
    return NSExtensionMain(argc, argv);
}
