#import "CLNativeImport.h"

NSString *const CLNativeImportErrorDomain = @"local.aichat.chatlog.native-import";
const unsigned long long CLMaximumZIPBytes = 64ULL * 1024ULL * 1024ULL;

static NSError *CLError(CLNativeImportErrorCode code, NSString *message) {
    return [NSError errorWithDomain:CLNativeImportErrorDomain
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: message}];
}

@interface CLLocalRedirectBlocker : NSObject <NSURLSessionTaskDelegate>
@end

@implementation CLLocalRedirectBlocker
- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
willPerformHTTPRedirection:(NSHTTPURLResponse *)response
        newRequest:(NSURLRequest *)request
  completionHandler:(void (^)(NSURLRequest * _Nullable))completionHandler {
    completionHandler(nil);
}
@end

static NSURL *CLSafeBaseURL(NSDictionary *config, NSError **error) {
    id rawURL = config[@"url"];
    id token = config[@"token"];
    if (![rawURL isKindOfClass:NSString.class] || ![token isKindOfClass:NSString.class] || [token length] == 0) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"本地服务配置不完整。");
        return nil;
    }
    NSURL *url = [NSURL URLWithString:rawURL];
    BOOL exact = [url.scheme isEqualToString:@"http"] &&
                 [url.host isEqualToString:@"127.0.0.1"] &&
                 url.port.integerValue == 48742 &&
                 ([url.path isEqualToString:@""] || [url.path isEqualToString:@"/"]) &&
                 url.user == nil && url.password == nil && url.query == nil && url.fragment == nil;
    if (!exact) {
        if (error) *error = CLError(CLNativeImportErrorUnsafeServer, @"只允许连接固定的本地日志服务。");
        return nil;
    }
    return url;
}

NSDictionary *CLLoadNativeConfig(NSBundle *bundle, NSError **error) {
    NSURL *url = [bundle URLForResource:@"config" withExtension:@"json"];
    if (!url) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"找不到本地服务配置。");
        return nil;
    }
    NSData *data = [NSData dataWithContentsOfURL:url options:0 error:error];
    if (!data) return nil;
    id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:error];
    if (![object isKindOfClass:NSDictionary.class]) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"本地服务配置格式无效。");
        return nil;
    }
    NSDictionary *config = object;
    if (!CLSafeBaseURL(config, error)) return nil;
    if (![config[@"python"] isKindOfClass:NSString.class] ||
        ![config[@"server"] isKindOfClass:NSString.class]) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"本地服务启动配置不完整。");
        return nil;
    }
    return config;
}

NSData *CLReadValidatedZIP(NSURL *url, NSError **error) {
    if (!url.isFileURL) {
        if (error) *error = CLError(CLNativeImportErrorNotFileURL, @"只能导入本机 ZIP 文件。");
        return nil;
    }
    if (![url.pathExtension.lowercaseString isEqualToString:@"zip"]) {
        if (error) *error = CLError(CLNativeImportErrorWrongType, @"请选择微信导出的 ZIP 消息包。");
        return nil;
    }
    NSNumber *regular = nil;
    NSNumber *size = nil;
    NSError *resourceError = nil;
    if (![url getResourceValue:&regular forKey:NSURLIsRegularFileKey error:&resourceError] || !regular.boolValue ||
        ![url getResourceValue:&size forKey:NSURLFileSizeKey error:&resourceError]) {
        if (error) *error = resourceError ?: CLError(CLNativeImportErrorMissingFile, @"无法读取所选 ZIP 文件。");
        return nil;
    }
    if (size.unsignedLongLongValue == 0 || size.unsignedLongLongValue > CLMaximumZIPBytes) {
        if (error) *error = CLError(CLNativeImportErrorTooLarge, @"ZIP 文件为空或超过 64 MB。");
        return nil;
    }
    NSData *data = [NSData dataWithContentsOfURL:url options:NSDataReadingMappedIfSafe error:error];
    if (!data && error && !*error) *error = CLError(CLNativeImportErrorMissingFile, @"无法读取所选 ZIP 文件。");
    return data;
}

NSMutableURLRequest *CLCreateImportRequest(NSDictionary *config, NSData *body, NSString *target, NSError **error) {
    NSURL *baseURL = CLSafeBaseURL(config, error);
    if (!baseURL) return nil;
    if (body.length == 0 || body.length > CLMaximumZIPBytes || target.length == 0) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"导入请求参数无效。");
        return nil;
    }
    NSURL *endpoint = [NSURL URLWithString:@"v1/import" relativeToURL:baseURL].absoluteURL;
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:endpoint
                                                          cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                                      timeoutInterval:30.0];
    request.HTTPMethod = @"POST";
    request.HTTPBody = body;
    [request setValue:@"application/zip" forHTTPHeaderField:@"Content-Type"];
    [request setValue:config[@"token"] forHTTPHeaderField:@"X-Logbook-Token"];
    [request setValue:target forHTTPHeaderField:@"X-Logbook-Target"];
    return request;
}

NSURLRequest *CLCreateHealthRequest(NSDictionary *config, NSError **error) {
    NSURL *baseURL = CLSafeBaseURL(config, error);
    if (!baseURL) return nil;
    NSURL *endpoint = [NSURL URLWithString:@"health" relativeToURL:baseURL].absoluteURL;
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:endpoint
                                                          cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                                      timeoutInterval:1.0];
    [request setValue:@"application/json" forHTTPHeaderField:@"Accept"];
    return request;
}

NSDictionary *CLServiceLaunchInfo(NSDictionary *config, NSError **error) {
    NSString *python = config[@"python"];
    NSString *server = config[@"server"];
    if (![python isKindOfClass:NSString.class] || !python.isAbsolutePath ||
        ![server isKindOfClass:NSString.class] || !server.isAbsolutePath) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"日志服务启动路径无效。");
        return nil;
    }
    NSString *serverDirectory = server.stringByDeletingLastPathComponent;
    NSString *workspace = serverDirectory.stringByDeletingLastPathComponent.stringByDeletingLastPathComponent.stringByDeletingLastPathComponent;
    NSString *dataDirectory = [workspace stringByAppendingPathComponent:@"work/chatlog"];
    NSString *webDirectory = [serverDirectory.stringByDeletingLastPathComponent stringByAppendingPathComponent:@"web/dist"];
    return @{
        @"executable": python,
        @"cwd": workspace,
        @"arguments": [server.lastPathComponent isEqualToString:@"launch.py"] ? @[server] : @[server, @"--port", @"48742", @"--data", dataDirectory, @"--web", webDirectory],
    };
}

BOOL CLIsHealthyResponse(NSData *data, NSInteger statusCode) {
    if (statusCode != 200 || !data) return NO;
    id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    if (![object isKindOfClass:NSDictionary.class]) return NO;
    NSDictionary *json = object;
    return [json[@"app"] isEqualToString:@"AiChatLog"] && [json[@"version"] integerValue] == 1;
}

NSDictionary *CLParseImportResponse(NSData *data, NSInteger statusCode, NSError **error) {
    id object = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    NSDictionary *json = [object isKindOfClass:NSDictionary.class] ? object : nil;
    if (statusCode == 200 && [json[@"ok"] boolValue]) return json;
    NSString *message = [json[@"error"] isKindOfClass:NSString.class] ? json[@"error"] : @"日志服务返回了无效响应。";
    if (error) *error = CLError(CLNativeImportErrorInvalidResponse, message);
    return nil;
}

NSURLSession *CLCreatePinnedLocalSession(NSTimeInterval timeout) {
    NSURLSessionConfiguration *configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration;
    configuration.timeoutIntervalForRequest = timeout;
    configuration.timeoutIntervalForResource = timeout;
    CLLocalRedirectBlocker *delegate = [[CLLocalRedirectBlocker alloc] init];
    return [NSURLSession sessionWithConfiguration:configuration delegate:delegate delegateQueue:nil];
}

BOOL CLIsAttachmentURL(NSURL *url) {
    return [url.scheme isEqualToString:@"http"] &&
           [url.host isEqualToString:@"127.0.0.1"] &&
           url.port.integerValue == 48742 &&
           [url.path hasPrefix:@"/v1/attachment/"] &&
           url.user == nil && url.password == nil;
}

BOOL CLDownloadDestinationIsSafe(NSURL *url, NSError **error) {
    if (!url.isFileURL) {
        if (error) *error = CLError(CLNativeImportErrorInvalidConfig, @"请选择本机文件夹中的保存位置。");
        return NO;
    }
    BOOL directory = NO;
    if ([NSFileManager.defaultManager fileExistsAtPath:url.path isDirectory:&directory]) {
        if (error) *error = CLError(CLNativeImportErrorDestinationExists, @"该位置已有同名文件，请选择其他文件名。");
        return NO;
    }
    return YES;
}

NSURL *CLDefaultPendingInboxURL(void) {
    NSURL *support = [[NSFileManager defaultManager] URLsForDirectory:NSApplicationSupportDirectory
                                                           inDomains:NSUserDomainMask].firstObject;
    return [[support URLByAppendingPathComponent:@"聊天日志" isDirectory:YES]
                    URLByAppendingPathComponent:@"Pending Imports" isDirectory:YES];
}

NSURL *CLShareExtensionPendingInboxURL(void) {
    NSString *path = [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Containers/local.aichat.chatlog.share/Data/Library/Application Support/聊天日志/Pending Imports"];
    return [NSURL fileURLWithPath:path isDirectory:YES];
}

NSURL *CLPreservePendingZIP(NSURL *source, NSURL *inbox, NSString *displayTarget, NSError **error) {
    NSData *data = CLReadValidatedZIP(source, error);
    if (!data) return nil;
    NSFileManager *manager = NSFileManager.defaultManager;
    if (![manager createDirectoryAtURL:inbox
           withIntermediateDirectories:YES
                            attributes:@{NSFilePosixPermissions: @0700}
                                 error:error]) return nil;
    [manager setAttributes:@{NSFilePosixPermissions: @0700} ofItemAtPath:inbox.path error:nil];
    NSString *filename = [NSString stringWithFormat:@"%@-%@.zip", NSUUID.UUID.UUIDString, source.lastPathComponent.stringByDeletingPathExtension];
    NSURL *destination = [inbox URLByAppendingPathComponent:filename];
    if (![data writeToURL:destination options:NSDataWritingAtomic error:error]) return nil;
    [manager setAttributes:@{NSFilePosixPermissions: @0600} ofItemAtPath:destination.path error:nil];
    NSDictionary *status = @{
        @"status": @"pending",
        @"target": displayTarget,
        @"createdAt": @((long long)(NSDate.date.timeIntervalSince1970 * 1000.0)),
        @"filename": source.lastPathComponent ?: @"微信导出.zip",
    };
    NSData *statusData = [NSJSONSerialization dataWithJSONObject:status options:0 error:error];
    if (!statusData) {
        [manager removeItemAtURL:destination error:nil];
        return nil;
    }
    NSURL *statusURL = [destination URLByAppendingPathExtension:@"pending.json"];
    if (![statusData writeToURL:statusURL options:NSDataWritingAtomic error:error]) {
        [manager removeItemAtURL:destination error:nil];
        return nil;
    }
    [manager setAttributes:@{NSFilePosixPermissions: @0600} ofItemAtPath:statusURL.path error:nil];
    return destination;
}

NSArray<NSURL *> *CLPendingZIPs(NSURL *inbox) {
    NSArray<NSURL *> *contents = [NSFileManager.defaultManager contentsOfDirectoryAtURL:inbox
                                                            includingPropertiesForKeys:@[NSURLIsRegularFileKey]
                                                                               options:NSDirectoryEnumerationSkipsHiddenFiles
                                                                                 error:nil] ?: @[];
    NSPredicate *zipOnly = [NSPredicate predicateWithBlock:^BOOL(NSURL *url, NSDictionary *bindings) {
        BOOL directory = NO;
        BOOL exists = [NSFileManager.defaultManager fileExistsAtPath:url.path isDirectory:&directory];
        return exists && !directory && [url.pathExtension.lowercaseString isEqualToString:@"zip"];
    }];
    return [[contents filteredArrayUsingPredicate:zipOnly] sortedArrayUsingComparator:^NSComparisonResult(NSURL *left, NSURL *right) {
        return [left.lastPathComponent compare:right.lastPathComponent options:NSCaseInsensitiveSearch];
    }];
}

void CLRemovePendingZIP(NSURL *url) {
    NSFileManager *manager = NSFileManager.defaultManager;
    [manager removeItemAtURL:url error:nil];
    [manager removeItemAtURL:[url URLByAppendingPathExtension:@"pending.json"] error:nil];
}

void CLProbeHealth(NSDictionary *config, CLHealthCompletion completion) {
    NSError *requestError = nil;
    NSURLRequest *request = CLCreateHealthRequest(config, &requestError);
    if (!request) {
        dispatch_async(dispatch_get_main_queue(), ^{ completion(NO, requestError); });
        return;
    }
    NSURLSession *session = CLCreatePinnedLocalSession(1.0);
    [[session dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        BOOL healthy = !error && CLIsHealthyResponse(data, [(NSHTTPURLResponse *)response statusCode]);
        dispatch_async(dispatch_get_main_queue(), ^{ completion(healthy, error); });
        [session finishTasksAndInvalidate];
    }] resume];
}

void CLSendZIP(NSURL *url, NSDictionary *config, CLImportCompletion completion) {
    NSError *error = nil;
    BOOL accessed = [url startAccessingSecurityScopedResource];
    NSData *body = CLReadValidatedZIP(url, &error);
    if (!body) {
        if (accessed) [url stopAccessingSecurityScopedResource];
        dispatch_async(dispatch_get_main_queue(), ^{ completion(nil, error); });
        return;
    }
    NSMutableURLRequest *request = CLCreateImportRequest(config, body, @"target", &error);
    if (!request) {
        if (accessed) [url stopAccessingSecurityScopedResource];
        dispatch_async(dispatch_get_main_queue(), ^{ completion(nil, error); });
        return;
    }
    NSString *escapedFilename = [url.lastPathComponent stringByAddingPercentEncodingWithAllowedCharacters:NSCharacterSet.URLPathAllowedCharacterSet];
    if (escapedFilename.length) [request setValue:escapedFilename forHTTPHeaderField:@"X-Logbook-Filename"];
    NSURLSession *session = CLCreatePinnedLocalSession(30.0);
    [[session dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *transportError) {
        if (accessed) [url stopAccessingSecurityScopedResource];
        NSError *resultError = transportError;
        NSDictionary *json = nil;
        NSInteger status = [(NSHTTPURLResponse *)response statusCode];
        if (!resultError) json = CLParseImportResponse(data, status, &resultError);
        dispatch_async(dispatch_get_main_queue(), ^{ completion(resultError ? nil : json, resultError); });
        [session finishTasksAndInvalidate];
    }] resume];
}
