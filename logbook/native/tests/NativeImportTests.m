#import <Foundation/Foundation.h>
#import "CLNativeImport.h"

static int failures = 0;

static void Check(BOOL condition, NSString *message) {
    if (!condition) {
        fprintf(stderr, "FAIL: %s\n", message.UTF8String);
        failures += 1;
    }
}

static NSURL *WriteFile(NSURL *directory, NSString *name, NSData *data) {
    NSURL *url = [directory URLByAppendingPathComponent:name];
    [data writeToURL:url atomically:YES];
    return url;
}

static void TestZIPValidation(NSURL *temporaryDirectory) {
    NSError *error = nil;
    NSURL *valid = WriteFile(temporaryDirectory, @"export.ZIP", [@"PK\x03\x04payload" dataUsingEncoding:NSUTF8StringEncoding]);
    NSData *data = CLReadValidatedZIP(valid, &error);
    Check(data.length == 11 && error == nil, @"a readable .zip file is accepted byte-for-byte");

    error = nil;
    NSURL *wrongType = WriteFile(temporaryDirectory, @"export.txt", [@"PK\x03\x04payload" dataUsingEncoding:NSUTF8StringEncoding]);
    Check(CLReadValidatedZIP(wrongType, &error) == nil && error.code == CLNativeImportErrorWrongType,
          @"a non-ZIP extension is rejected explicitly");

    error = nil;
    NSURL *remote = [NSURL URLWithString:@"https://example.invalid/export.zip"];
    Check(CLReadValidatedZIP(remote, &error) == nil && error.code == CLNativeImportErrorNotFileURL,
          @"a non-file URL is rejected");

    NSURL *oversize = [temporaryDirectory URLByAppendingPathComponent:@"oversize.zip"];
    [[NSFileManager defaultManager] createFileAtPath:oversize.path contents:[NSData data] attributes:nil];
    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:oversize.path];
    [handle truncateFileAtOffset:CLMaximumZIPBytes + 1];
    [handle closeFile];
    error = nil;
    Check(CLReadValidatedZIP(oversize, &error) == nil && error.code == CLNativeImportErrorTooLarge,
          @"a ZIP larger than 64 MiB is rejected before loading");
}

static void TestRequestContract(void) {
    NSDictionary *config = @{
        @"url": @"http://127.0.0.1:48742/",
        @"token": @"secret-for-test",
    };
    NSData *body = [@"PK\x03\x04bytes" dataUsingEncoding:NSUTF8StringEncoding];
    NSError *error = nil;
    NSURLRequest *request = CLCreateImportRequest(config, body, @"target", &error);
    Check(error == nil, @"valid request configuration has no error");
    Check([request.URL.absoluteString isEqualToString:@"http://127.0.0.1:48742/v1/import"], @"request uses the fixed import endpoint");
    Check([request.HTTPMethod isEqualToString:@"POST"], @"request uses POST");
    Check([[request valueForHTTPHeaderField:@"Content-Type"] isEqualToString:@"application/zip"], @"request declares ZIP content");
    Check([[request valueForHTTPHeaderField:@"X-Logbook-Token"] isEqualToString:@"secret-for-test"], @"request includes token header");
    Check([[request valueForHTTPHeaderField:@"X-Logbook-Target"] isEqualToString:@"target"], @"request includes fixed target header");
    Check([request.HTTPBody isEqualToData:body], @"request carries the original bytes");
    Check(request.timeoutInterval == 30.0, @"request has a 30 second timeout");

    error = nil;
    NSDictionary *wrongHost = @{@"url": @"http://localhost:48742/", @"token": @"x"};
    Check(CLCreateImportRequest(wrongHost, body, @"target", &error) == nil && error.code == CLNativeImportErrorUnsafeServer,
          @"request refuses a server URL that is not the exact loopback host and port");
}

static void TestResponseAndTransportSafety(void) {
    NSData *health = [@"{\"app\":\"AiChatLog\",\"version\":1}" dataUsingEncoding:NSUTF8StringEncoding];
    NSData *array = [@"[]" dataUsingEncoding:NSUTF8StringEncoding];
    NSError *error = nil;
    Check(CLIsHealthyResponse(health, 200), @"health parser accepts the exact object contract");
    Check(!CLIsHealthyResponse(array, 200), @"health parser rejects a JSON array without keyed access");

    NSDictionary *result = CLParseImportResponse([@"{\"ok\":true,\"added\":1}" dataUsingEncoding:NSUTF8StringEncoding], 200, &error);
    Check([result[@"ok"] boolValue] && error == nil, @"import parser accepts a successful object response");
    error = nil;
    Check(CLParseImportResponse(array, 200, &error) == nil && error.code == CLNativeImportErrorInvalidResponse,
          @"import parser rejects non-object JSON without crashing");

    NSURLSession *session = CLCreatePinnedLocalSession(30.0);
    id<NSURLSessionTaskDelegate> delegate = (id<NSURLSessionTaskDelegate>)session.delegate;
    NSURLRequest *redirect = [NSURLRequest requestWithURL:[NSURL URLWithString:@"https://example.invalid/stolen"]];
    NSHTTPURLResponse *response = [[NSHTTPURLResponse alloc] initWithURL:[NSURL URLWithString:@"http://127.0.0.1:48742/v1/import"]
                                                              statusCode:302 HTTPVersion:@"HTTP/1.1" headerFields:@{}];
    NSURLSessionDataTask *task = [session dataTaskWithURL:[NSURL URLWithString:@"http://127.0.0.1:48742/health"]];
    __block NSURLRequest *followed = redirect;
    [delegate URLSession:session task:task willPerformHTTPRedirection:response newRequest:redirect completionHandler:^(NSURLRequest *request) {
        followed = request;
    }];
    Check(followed == nil, @"native sessions reject redirects so token and ZIP bytes stay on loopback");
    [session invalidateAndCancel];

    Check(CLIsAttachmentURL([NSURL URLWithString:@"http://127.0.0.1:48742/v1/attachment/a"]),
          @"attachment download accepts the authenticated local route");
    Check(!CLIsAttachmentURL([NSURL URLWithString:@"https://example.invalid/v1/attachment/a"]),
          @"attachment download rejects an external host");
    Check(!CLIsAttachmentURL([NSURL URLWithString:@"http://127.0.0.1:48742/v1/attachmentish/a"]),
          @"attachment download rejects a lookalike route");
}

static void TestServiceLaunchPaths(void) {
    NSDictionary *config = @{
        @"python": @"/usr/bin/python3",
        @"server": @"/workspace/outputs/demo/logbook/server.py",
        @"url": @"http://127.0.0.1:48742/",
        @"token": @"test",
    };
    NSError *error = nil;
    NSDictionary *launch = CLServiceLaunchInfo(config, &error);
    NSArray *expectedArguments = @[
        @"/workspace/outputs/demo/logbook/server.py", @"--port", @"48742",
        @"--data", @"/workspace/work/chatlog",
        @"--web", @"/workspace/outputs/demo/web/dist",
    ];
    Check(error == nil && [launch[@"arguments"] isEqualToArray:expectedArguments],
          @"service launch resolves data and web paths from the workspace layout");
    Check([launch[@"cwd"] isEqualToString:@"/workspace"], @"service starts with the workspace as its current directory");
    NSMutableDictionary *launcherConfig = config.mutableCopy;
    launcherConfig[@"server"] = @"/workspace/outputs/demo/logbook/launch.py";
    NSDictionary *launcher = CLServiceLaunchInfo(launcherConfig, &error);
    Check([launcher[@"arguments"] isEqualToArray:@[@"/workspace/outputs/demo/logbook/launch.py"]], @"launcher starts logger, viewer and dsh history host without server-only arguments");
}

static void TestPendingPreservation(NSURL *temporaryDirectory) {
    NSData *original = [@"PK\x03\x04pending-payload" dataUsingEncoding:NSUTF8StringEncoding];
    NSURL *source = WriteFile(temporaryDirectory, @"wechat-export.zip", original);
    NSURL *inbox = [temporaryDirectory URLByAppendingPathComponent:@"private-inbox" isDirectory:YES];
    NSError *error = nil;
    NSURL *saved = CLPreservePendingZIP(source, inbox, @"卧底不追高", &error);
    Check(saved != nil && error == nil, @"unavailable-service fallback preserves the archive");
    Check([[[NSData alloc] initWithContentsOfURL:saved] isEqualToData:original], @"pending archive bytes are unchanged");
    NSURL *statusURL = [saved URLByAppendingPathExtension:@"pending.json"];
    NSDictionary *status = [NSJSONSerialization JSONObjectWithData:[NSData dataWithContentsOfURL:statusURL] options:0 error:&error];
    Check([status[@"status"] isEqualToString:@"pending"], @"pending archive has an explicit status record");
    Check([status[@"target"] isEqualToString:@"卧底不追高"], @"pending status records the user-selected target");
    NSNumber *permissions = [[NSFileManager defaultManager] attributesOfItemAtPath:saved.path error:nil][NSFilePosixPermissions];
    Check(permissions.unsignedShortValue == 0600, @"pending archive is owner-readable only");

    NSURL *second = WriteFile(inbox, @"aaa.zip", [@"PK\x03\x04second" dataUsingEncoding:NSUTF8StringEncoding]);
    WriteFile(inbox, @"ignore.txt", [@"ignore" dataUsingEncoding:NSUTF8StringEncoding]);
    NSArray<NSURL *> *pending = CLPendingZIPs(inbox);
    NSSet *pendingNames = [NSSet setWithArray:[pending valueForKey:@"lastPathComponent"]];
    Check(pending.count == 2 && [pendingNames containsObject:saved.lastPathComponent] && [pendingNames containsObject:second.lastPathComponent],
          @"pending drain lists only ZIP archives");
    NSURL *secondStatus = [second URLByAppendingPathExtension:@"pending.json"];
    WriteFile(inbox, secondStatus.lastPathComponent, [@"{}" dataUsingEncoding:NSUTF8StringEncoding]);
    CLRemovePendingZIP(second);
    Check(![[NSFileManager defaultManager] fileExistsAtPath:second.path] &&
          ![[NSFileManager defaultManager] fileExistsAtPath:secondStatus.path],
          @"successful pending import removes its archive and status record together");
}

static void TestDownloadDestinationSafety(NSURL *temporaryDirectory) {
    NSData *original = [@"keep this user file" dataUsingEncoding:NSUTF8StringEncoding];
    NSURL *existing = WriteFile(temporaryDirectory, @"existing-attachment.bin", original);
    NSError *error = nil;
    Check(!CLDownloadDestinationIsSafe(existing, &error) && error != nil,
          @"attachment download refuses an existing destination");
    Check([[NSData dataWithContentsOfURL:existing] isEqualToData:original],
          @"refusing an existing destination preserves its bytes");
    error = nil;
    NSURL *unused = [temporaryDirectory URLByAppendingPathComponent:@"unused-attachment.bin"];
    Check(CLDownloadDestinationIsSafe(unused, &error) && error == nil,
          @"attachment download accepts an unused destination");
}

int main(void) {
    @autoreleasepool {
        NSString *base = [NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString];
        NSURL *temporaryDirectory = [NSURL fileURLWithPath:base isDirectory:YES];
        [[NSFileManager defaultManager] createDirectoryAtURL:temporaryDirectory withIntermediateDirectories:YES attributes:@{NSFilePosixPermissions: @0700} error:nil];
        TestZIPValidation(temporaryDirectory);
        TestRequestContract();
        TestResponseAndTransportSafety();
        TestServiceLaunchPaths();
        TestPendingPreservation(temporaryDirectory);
        TestDownloadDestinationSafety(temporaryDirectory);
        [[NSFileManager defaultManager] removeItemAtURL:temporaryDirectory error:nil];
    }
    if (failures == 0) {
        puts("PASS: native import assertions");
        return 0;
    }
    fprintf(stderr, "%d assertion(s) failed\n", failures);
    return 1;
}
