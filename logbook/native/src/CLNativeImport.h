#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSString *const CLNativeImportErrorDomain;
FOUNDATION_EXPORT const unsigned long long CLMaximumZIPBytes;

typedef NS_ENUM(NSInteger, CLNativeImportErrorCode) {
    CLNativeImportErrorNotFileURL = 1,
    CLNativeImportErrorWrongType = 2,
    CLNativeImportErrorMissingFile = 3,
    CLNativeImportErrorTooLarge = 4,
    CLNativeImportErrorUnsafeServer = 5,
    CLNativeImportErrorInvalidConfig = 6,
    CLNativeImportErrorInvalidResponse = 7,
    CLNativeImportErrorDestinationExists = 8,
};

FOUNDATION_EXPORT NSDictionary * _Nullable CLLoadNativeConfig(NSBundle *bundle, NSError **error);
FOUNDATION_EXPORT NSData * _Nullable CLReadValidatedZIP(NSURL *url, NSError **error);
FOUNDATION_EXPORT NSMutableURLRequest * _Nullable CLCreateImportRequest(NSDictionary *config, NSData *body, NSString *target, NSError **error);
FOUNDATION_EXPORT NSURLRequest * _Nullable CLCreateHealthRequest(NSDictionary *config, NSError **error);
FOUNDATION_EXPORT NSDictionary * _Nullable CLServiceLaunchInfo(NSDictionary *config, NSError **error);
FOUNDATION_EXPORT BOOL CLIsHealthyResponse(NSData *data, NSInteger statusCode);
FOUNDATION_EXPORT NSDictionary * _Nullable CLParseImportResponse(NSData * _Nullable data, NSInteger statusCode, NSError **error);
FOUNDATION_EXPORT NSURLSession *CLCreatePinnedLocalSession(NSTimeInterval timeout);
FOUNDATION_EXPORT BOOL CLIsAttachmentURL(NSURL *url);
FOUNDATION_EXPORT BOOL CLDownloadDestinationIsSafe(NSURL *url, NSError **error);
FOUNDATION_EXPORT NSURL *CLDefaultPendingInboxURL(void);
FOUNDATION_EXPORT NSURL *CLShareExtensionPendingInboxURL(void);
FOUNDATION_EXPORT NSURL * _Nullable CLPreservePendingZIP(NSURL *source, NSURL *inbox, NSString *displayTarget, NSError **error);
FOUNDATION_EXPORT NSArray<NSURL *> *CLPendingZIPs(NSURL *inbox);
FOUNDATION_EXPORT void CLRemovePendingZIP(NSURL *url);

typedef void (^CLHealthCompletion)(BOOL healthy, NSError * _Nullable error);
typedef void (^CLImportCompletion)(NSDictionary * _Nullable result, NSError * _Nullable error);

FOUNDATION_EXPORT void CLProbeHealth(NSDictionary *config, CLHealthCompletion completion);
FOUNDATION_EXPORT void CLSendZIP(NSURL *url, NSDictionary *config, CLImportCompletion completion);

NS_ASSUME_NONNULL_END
