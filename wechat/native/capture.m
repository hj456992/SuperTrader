#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <Vision/Vision.h>

static NSString *const WeChatBundleIdentifier = @"com.tencent.xinWeChat";

static NSDictionary *emptyFrame(NSString *status) {
    return @{
        @"status": status,
        @"windowId": @0,
        @"title": @"",
        @"width": @0,
        @"height": @0,
        @"chatLeft": @0,
        @"chatTop": @0,
        @"chatBottom": @0,
        @"lines": @[],
    };
}

static void emitJSON(NSDictionary *value) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
    if (data == nil) {
        data = [@"{\"status\":\"capture_error\",\"windowId\":0,\"title\":\"\",\"width\":0,\"height\":0,\"chatLeft\":0,\"chatTop\":0,\"chatBottom\":0,\"lines\":[]}" dataUsingEncoding:NSUTF8StringEncoding];
    }
    fwrite(data.bytes, 1, data.length, stdout);
    fputc('\n', stdout);
    fflush(stdout);
}

static NSArray<VNRecognizedTextObservation *> *recognize(CGImageRef image,
                                                          NSString *customWord,
                                                          float minimumTextHeight) {
    VNRecognizeTextRequest *request = [[VNRecognizeTextRequest alloc] init];
    request.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
    request.recognitionLanguages = @[@"zh-Hans", @"en-US"];
    request.usesLanguageCorrection = YES;
    request.automaticallyDetectsLanguage = NO;
    request.minimumTextHeight = minimumTextHeight;
    if (customWord.length > 0) {
        request.customWords = @[customWord];
    }

    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:image options:@{}];
    NSError *error = nil;
    if (![handler performRequests:@[request] error:&error] || error != nil) {
        return nil;
    }
    return request.results ?: @[];
}

static NSString *compactText(NSString *value);
static BOOL normalizedTitleMatches(NSString *title, NSString *target);
static NSDictionary *selectTitleLine(NSArray<NSDictionary *> *observations, NSString *target);

static NSString *recognizedTargetTitle(CGImageRef image,
                                       CGFloat windowWidth,
                                       CGFloat chatTop,
                                       CGFloat scaleX,
                                       CGFloat scaleY,
                                       NSString *target,
                                       CGRect *matchedRectPoints,
                                       BOOL *requestSucceeded,
                                       BOOL *sawText) {
    *requestSucceeded = NO;
    *sawText = NO;
    CGRect titlePixels = CGRectMake(0, 0, windowWidth * scaleX, chatTop * scaleY);
    CGImageRef cropped = CGImageCreateWithImageInRect(image, titlePixels);
    if (cropped == nil) {
        return nil;
    }
    NSArray<VNRecognizedTextObservation *> *observations = recognize(cropped, nil, 0.03f);
    CGImageRelease(cropped);
    if (observations == nil) {
        return nil;
    }
    *requestSucceeded = YES;

    NSMutableArray<NSDictionary *> *titleObservations = [NSMutableArray array];
    for (VNRecognizedTextObservation *observation in observations) {
        VNRecognizedText *candidate = [[observation topCandidates:1] firstObject];
        if (candidate.string.length == 0) {
            continue;
        }
        *sawText = YES;
        CGRect normalized = observation.boundingBox;
        CGRect rect = CGRectMake(
            CGRectGetMinX(normalized) * windowWidth,
            (1.0 - CGRectGetMaxY(normalized)) * chatTop,
            CGRectGetWidth(normalized) * windowWidth,
            CGRectGetHeight(normalized) * chatTop);
        [titleObservations addObject:@{
            @"candidates": @[candidate.string],
            @"rect": [NSValue valueWithRect:NSRectFromCGRect(rect)],
        }];
    }
    NSDictionary *selection = selectTitleLine(titleObservations, target);
    if (selection == nil) {
        return nil;
    }
    *matchedRectPoints = NSRectToCGRect([selection[@"rect"] rectValue]);
    return selection[@"text"];
}

static NSString *compactText(NSString *value) {
    return [[value componentsSeparatedByCharactersInSet:
        [NSCharacterSet whitespaceAndNewlineCharacterSet]] componentsJoinedByString:@""];
}

static BOOL normalizedTitleMatches(NSString *title, NSString *target) {
    NSString *compactTitle = compactText(title);
    NSString *compactTarget = compactText(target);
    NSString *escapedTarget = [NSRegularExpression escapedPatternForString:compactTarget];
    NSString *pattern = [NSString stringWithFormat:@"^%@(\\([0-9]+\\)|（[0-9]+）)?$", escapedTarget];
    NSRegularExpression *expression = [NSRegularExpression regularExpressionWithPattern:pattern
                                                                                 options:0 error:nil];
    NSRange whole = NSMakeRange(0, compactTitle.length);
    return [expression firstMatchInString:compactTitle options:0 range:whole] != nil;
}

static NSDictionary *selectTitleLine(NSArray<NSDictionary *> *observations, NSString *target) {
    NSMutableArray<NSDictionary *> *strongest = [NSMutableArray array];
    for (NSDictionary *observation in observations) {
        NSString *candidate = [observation[@"candidates"] firstObject];
        NSRect rect = [observation[@"rect"] rectValue];
        if (candidate.length > 0 && NSMinX(rect) >= 220.0) {
            [strongest addObject:@{@"text": candidate, @"rect": observation[@"rect"]}];
        }
    }
    [strongest sortUsingComparator:^NSComparisonResult(NSDictionary *left, NSDictionary *right) {
        NSRect leftRect = [left[@"rect"] rectValue];
        NSRect rightRect = [right[@"rect"] rectValue];
        if (NSMinY(leftRect) != NSMinY(rightRect)) {
            return NSMinY(leftRect) < NSMinY(rightRect) ? NSOrderedAscending : NSOrderedDescending;
        }
        return NSMinX(leftRect) < NSMinX(rightRect) ? NSOrderedAscending : NSOrderedDescending;
    }];

    NSMutableIndexSet *used = [NSMutableIndexSet indexSet];
    NSDictionary *matched = nil;
    for (NSUInteger seed = 0; seed < strongest.count; seed++) {
        if ([used containsIndex:seed]) {
            continue;
        }
        NSMutableIndexSet *lineIndexes = [NSMutableIndexSet indexSetWithIndex:seed];
        NSRect lineRect = [strongest[seed][@"rect"] rectValue];
        BOOL expanded = YES;
        while (expanded) {
            expanded = NO;
            for (NSUInteger index = 0; index < strongest.count; index++) {
                if ([lineIndexes containsIndex:index] || [used containsIndex:index]) {
                    continue;
                }
                NSRect rect = [strongest[index][@"rect"] rectValue];
                CGFloat verticalOverlap = MIN(NSMaxY(lineRect), NSMaxY(rect)) -
                                          MAX(NSMinY(lineRect), NSMinY(rect));
                CGFloat requiredOverlap = MIN(NSHeight(lineRect), NSHeight(rect)) * 0.5;
                if (verticalOverlap >= requiredOverlap) {
                    [lineIndexes addIndex:index];
                    lineRect = NSUnionRect(lineRect, rect);
                    expanded = YES;
                }
            }
        }
        [used addIndexes:lineIndexes];
        NSMutableArray<NSDictionary *> *line = [NSMutableArray array];
        [lineIndexes enumerateIndexesUsingBlock:^(NSUInteger index, BOOL *stop) {
            (void)stop;
            [line addObject:strongest[index]];
        }];
        [line sortUsingComparator:^NSComparisonResult(NSDictionary *left, NSDictionary *right) {
            return NSMinX([left[@"rect"] rectValue]) < NSMinX([right[@"rect"] rectValue])
                ? NSOrderedAscending : NSOrderedDescending;
        }];
        NSMutableString *completeLine = [NSMutableString string];
        for (NSDictionary *part in line) {
            [completeLine appendString:compactText(part[@"text"])];
        }
        if (!normalizedTitleMatches(completeLine, target)) {
            continue;
        }
        if (matched != nil) {
            return nil;
        }
        matched = @{@"text": [completeLine copy],
                    @"rect": [NSValue valueWithRect:lineRect]};
    }
    return matched;
}

typedef struct {
    unsigned char *bytes;
    size_t width;
    size_t height;
    size_t stride;
} PixelBuffer;

static PixelBuffer makePixelBuffer(CGImageRef image) {
    PixelBuffer buffer = {0};
    buffer.width = CGImageGetWidth(image);
    buffer.height = CGImageGetHeight(image);
    buffer.stride = buffer.width * 4;
    if (buffer.width == 0 || buffer.height == 0) {
        return buffer;
    }
    buffer.bytes = calloc(buffer.height, buffer.stride);
    if (buffer.bytes == NULL) {
        return buffer;
    }
    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(buffer.bytes, buffer.width, buffer.height, 8,
                                                  buffer.stride, colorSpace,
                                                  kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(colorSpace);
    if (context == nil) {
        free(buffer.bytes);
        buffer.bytes = NULL;
        return buffer;
    }
    CGContextDrawImage(context, CGRectMake(0, 0, buffer.width, buffer.height), image);
    CGContextRelease(context);
    return buffer;
}

typedef NS_ENUM(uint8_t, BubblePixelKind) {
    BubblePixelNone = 0,
    BubblePixelLight = 1,
    BubblePixelDark = 2,
    BubblePixelGreen = 3,
};

static BubblePixelKind bubblePixelKind(PixelBuffer buffer, CGFloat pointX, CGFloat pointY) {
    if (buffer.bytes == NULL || pointX < 0 || pointY < 0 ||
        pointX >= buffer.width || pointY >= buffer.height) {
        return BubblePixelNone;
    }
    size_t x = (size_t)pointX;
    size_t y = (size_t)pointY;
    unsigned char *pixel = buffer.bytes + y * buffer.stride + x * 4;
    int red = pixel[0];
    int green = pixel[1];
    int blue = pixel[2];
    BOOL outgoingGreen = green >= 185 && green >= red + 20 && green >= blue + 35;
    BOOL incomingWhite = red >= 248 && green >= 248 && blue >= 248;
    BOOL incomingDark = red >= 39 && red <= 72 &&
                        abs(red - green) <= 6 && abs(green - blue) <= 6;
    if (outgoingGreen) {
        return BubblePixelGreen;
    }
    if (incomingWhite) {
        return BubblePixelLight;
    }
    if (incomingDark) {
        return BubblePixelDark;
    }
    return BubblePixelNone;
}

static BOOL componentReachesBubbleTail(PixelBuffer buffer,
                                       BubblePixelKind kind,
                                       CGRect seedRectPixels,
                                       CGFloat scaleX,
                                       CGFloat scaleY,
                                       CGFloat chatLeft,
                                       CGFloat chatTop,
                                       CGFloat bodyWidth) {
    BOOL outgoing = kind == BubblePixelGreen;
    CGFloat bodyRight = chatLeft + bodyWidth;
    CGFloat tailMinPoints = outgoing ? bodyRight - 69.0 : chatLeft + 65.0;
    CGFloat tailMaxPoints = outgoing ? bodyRight - 65.0 : chatLeft + 69.0;
    CGFloat edgeMinPoints = outgoing ? bodyRight - 74.0 : chatLeft + 70.0;
    CGFloat edgeMaxPoints = outgoing ? bodyRight - 70.0 : chatLeft + 74.0;

    size_t tailMinX = (size_t)MAX(0.0, floor(tailMinPoints * scaleX));
    size_t tailMaxX = (size_t)MIN((CGFloat)buffer.width - 1.0, ceil(tailMaxPoints * scaleX));
    size_t edgeMinX = (size_t)MAX(0.0, floor(edgeMinPoints * scaleX));
    size_t edgeMaxX = (size_t)MIN((CGFloat)buffer.width - 1.0, ceil(edgeMaxPoints * scaleX));
    size_t roiMinX = outgoing
        ? (size_t)MAX(0.0, floor(chatLeft * scaleX))
        : tailMinX;
    size_t roiMaxX = outgoing
        ? tailMaxX
        : (size_t)MIN((CGFloat)buffer.width - 1.0, ceil(bodyRight * scaleX));
    size_t roiMinY = (size_t)MAX(0.0, floor(chatTop * scaleY));
    size_t roiMaxY = buffer.height - 1;
    if (roiMinX > roiMaxX || roiMinY > roiMaxY) {
        return NO;
    }

    size_t seedMinX = (size_t)MAX((CGFloat)roiMinX, floor(CGRectGetMinX(seedRectPixels)));
    size_t seedMaxX = (size_t)MIN((CGFloat)roiMaxX, ceil(CGRectGetMaxX(seedRectPixels)));
    size_t seedMinY = (size_t)MAX((CGFloat)roiMinY, floor(CGRectGetMinY(seedRectPixels)));
    size_t seedMaxY = (size_t)MIN((CGFloat)roiMaxY, ceil(CGRectGetMaxY(seedRectPixels)));
    size_t seedX = 0;
    size_t seedY = 0;
    BOOL foundSeed = NO;
    for (size_t y = seedMinY; !foundSeed && y <= seedMaxY; y++) {
        for (size_t x = seedMinX; x <= seedMaxX; x++) {
            if (bubblePixelKind(buffer, x, y) == kind) {
                seedX = x;
                seedY = y;
                foundSeed = YES;
                break;
            }
        }
    }
    if (!foundSeed) {
        return NO;
    }

    size_t roiWidth = roiMaxX - roiMinX + 1;
    size_t roiHeight = roiMaxY - roiMinY + 1;
    if (roiWidth > SIZE_MAX / roiHeight) {
        return NO;
    }
    size_t area = roiWidth * roiHeight;
    unsigned char *visited = calloc(area, 1);
    size_t *queue = calloc(area, sizeof(size_t));
    if (visited == NULL || queue == NULL) {
        free(visited);
        free(queue);
        return NO;
    }

    size_t head = 0;
    size_t tail = 0;
    size_t seedIndex = (seedY - roiMinY) * roiWidth + (seedX - roiMinX);
    visited[seedIndex] = 1;
    queue[tail++] = seedIndex;
    NSUInteger tailPixels = 0;
    NSUInteger edgePixels = 0;
    while (head < tail) {
        size_t index = queue[head++];
        size_t localY = index / roiWidth;
        size_t localX = index % roiWidth;
        size_t x = roiMinX + localX;
        if (x >= tailMinX && x <= tailMaxX) {
            tailPixels++;
        }
        if (x >= edgeMinX && x <= edgeMaxX) {
            edgePixels++;
        }

        const int dx[] = {-1, 1, 0, 0};
        const int dy[] = {0, 0, -1, 1};
        for (NSUInteger direction = 0; direction < 4; direction++) {
            NSInteger nextLocalX = (NSInteger)localX + dx[direction];
            NSInteger nextLocalY = (NSInteger)localY + dy[direction];
            if (nextLocalX < 0 || nextLocalY < 0 ||
                nextLocalX >= (NSInteger)roiWidth || nextLocalY >= (NSInteger)roiHeight) {
                continue;
            }
            size_t nextIndex = (size_t)nextLocalY * roiWidth + (size_t)nextLocalX;
            if (visited[nextIndex]) {
                continue;
            }
            size_t nextX = roiMinX + (size_t)nextLocalX;
            size_t nextY = roiMinY + (size_t)nextLocalY;
            if (bubblePixelKind(buffer, nextX, nextY) != kind) {
                continue;
            }
            visited[nextIndex] = 1;
            queue[tail++] = nextIndex;
        }
    }
    free(visited);
    free(queue);
    NSUInteger minimumEvidence = (NSUInteger)MAX(2.0, floor(scaleX * scaleY * 2.0));
    return tailPixels >= minimumEvidence && edgePixels >= minimumEvidence;
}

static BOOL isBubbleText(PixelBuffer buffer, CGRect rectPoints, CGFloat scaleX, CGFloat scaleY,
                         CGFloat chatLeft, CGFloat chatTop, CGFloat bodyWidth,
                         BOOL *outgoing) {
    *outgoing = NO;
    if (CGRectGetMinY(rectPoints) <= chatTop + 2.0) {
        return NO;
    }
    CGFloat centerX = CGRectGetMidX(rectPoints);
    if (fabs(centerX - (chatLeft + bodyWidth / 2.0)) < 70.0 &&
        CGRectGetWidth(rectPoints) < 110.0 && CGRectGetHeight(rectPoints) < 20.0) {
        return NO;
    }
    CGFloat left = CGRectGetMinX(rectPoints) * scaleX;
    CGFloat right = CGRectGetMaxX(rectPoints) * scaleX;
    CGFloat top = CGRectGetMinY(rectPoints) * scaleY;
    CGFloat bottom = CGRectGetMaxY(rectPoints) * scaleY;
    CGFloat paddingX = MAX(2.0, 3.0 * scaleX);
    CGFloat paddingY = MAX(2.0, 2.0 * scaleY);
    left = MAX(0.0, left - paddingX);
    right = MIN((CGFloat)buffer.width - 1.0, right + paddingX);
    top = MAX(0.0, top - paddingY);
    bottom = MIN((CGFloat)buffer.height - 1.0, bottom + paddingY);
    NSUInteger step = (NSUInteger)MAX(1.0, floor(MIN(scaleX, scaleY) * 2.0));
    NSUInteger kindCounts[4] = {0, 0, 0, 0};
    NSUInteger samples = 0;
    for (NSUInteger y = (NSUInteger)top; y <= (NSUInteger)bottom; y += step) {
        for (NSUInteger x = (NSUInteger)left; x <= (NSUInteger)right; x += step) {
            samples++;
            BubblePixelKind kind = bubblePixelKind(buffer, x, y);
            kindCounts[kind]++;
        }
    }
    BubblePixelKind dominant = BubblePixelLight;
    for (BubblePixelKind kind = BubblePixelDark; kind <= BubblePixelGreen; kind++) {
        if (kindCounts[kind] > kindCounts[dominant]) {
            dominant = kind;
        }
    }
    if (dominant == BubblePixelNone || samples == 0 || kindCounts[dominant] * 4 < samples) {
        return NO;
    }
    CGRect seedRect = CGRectMake(left, top, right - left, bottom - top);
    BOOL hasTail = componentReachesBubbleTail(buffer, dominant, seedRect, scaleX, scaleY,
                                              chatLeft, chatTop, bodyWidth);
    *outgoing = hasTail && dominant == BubblePixelGreen;
    return hasTail;
}

static PixelBuffer solidPixelBuffer(size_t width, size_t height,
                                    unsigned char red, unsigned char green, unsigned char blue) {
    PixelBuffer buffer = {0};
    buffer.width = width;
    buffer.height = height;
    buffer.stride = width * 4;
    buffer.bytes = calloc(height, buffer.stride);
    if (buffer.bytes == NULL) {
        return buffer;
    }
    for (size_t y = 0; y < height; y++) {
        for (size_t x = 0; x < width; x++) {
            unsigned char *pixel = buffer.bytes + y * buffer.stride + x * 4;
            pixel[0] = red;
            pixel[1] = green;
            pixel[2] = blue;
            pixel[3] = 255;
        }
    }
    return buffer;
}

static void fillPixelRect(PixelBuffer buffer, CGRect rect,
                          unsigned char red, unsigned char green, unsigned char blue) {
    size_t minX = (size_t)MAX(0.0, floor(CGRectGetMinX(rect)));
    size_t maxX = (size_t)MIN((CGFloat)buffer.width, ceil(CGRectGetMaxX(rect)));
    size_t minY = (size_t)MAX(0.0, floor(CGRectGetMinY(rect)));
    size_t maxY = (size_t)MIN((CGFloat)buffer.height, ceil(CGRectGetMaxY(rect)));
    for (size_t y = minY; y < maxY; y++) {
        for (size_t x = minX; x < maxX; x++) {
            unsigned char *pixel = buffer.bytes + y * buffer.stride + x * 4;
            pixel[0] = red;
            pixel[1] = green;
            pixel[2] = blue;
            pixel[3] = 255;
        }
    }
}

static CGFloat deriveChatLeft(PixelBuffer buffer, CGRect titleRectPoints,
                              CGFloat scaleX, CGFloat scaleY,
                              CGFloat windowWidth, CGFloat chatTop, CGFloat chatBottom) {
    if (buffer.bytes == NULL || scaleX <= 0 || scaleY <= 0 ||
        CGRectGetMinX(titleRectPoints) < 220.0 || CGRectGetWidth(titleRectPoints) < 10.0 ||
        CGRectGetMinY(titleRectPoints) < 0 || CGRectGetMaxY(titleRectPoints) > chatTop + 4.0) {
        return -1.0;
    }
    CGFloat expected = round(CGRectGetMinX(titleRectPoints) - 15.0);
    if (expected < 200.0 || windowWidth - expected < 280.0) {
        return -1.0;
    }

    CGFloat bestCandidate = -1.0;
    double bestScore = 0.0;
    NSInteger offsets[] = {0, -1, 1, -2, 2};
    for (NSUInteger offsetIndex = 0;
         offsetIndex < sizeof(offsets) / sizeof(offsets[0]); offsetIndex++) {
        CGFloat candidate = expected + offsets[offsetIndex];
        NSInteger leftX = (NSInteger)floor((candidate - 2.0) * scaleX);
        NSInteger rightX = (NSInteger)ceil((candidate + 2.0) * scaleX);
        if (leftX < 0 || rightX < 0 || rightX >= (NSInteger)buffer.width) {
            continue;
        }
        size_t minY = (size_t)MAX(0.0, floor(chatTop * scaleY));
        size_t maxY = (size_t)MIN((CGFloat)buffer.height - 1.0, ceil(chatBottom * scaleY));
        size_t step = (size_t)MAX(1.0, floor(scaleY * 4.0));
        NSUInteger samples = 0;
        NSUInteger edges = 0;
        for (size_t y = minY; y <= maxY; y += step) {
            unsigned char *leftPixel = buffer.bytes + y * buffer.stride + (size_t)leftX * 4;
            unsigned char *rightPixel = buffer.bytes + y * buffer.stride + (size_t)rightX * 4;
            int difference = (abs((int)leftPixel[0] - (int)rightPixel[0]) +
                              abs((int)leftPixel[1] - (int)rightPixel[1]) +
                              abs((int)leftPixel[2] - (int)rightPixel[2])) / 3;
            samples++;
            if (difference >= 6) {
                edges++;
            }
        }
        double score = samples == 0 ? 0.0 : (double)edges / (double)samples;
        if (score > bestScore) {
            bestScore = score;
            bestCandidate = candidate;
        }
    }
    return bestScore >= 0.45 ? bestCandidate : -1.0;
}

static float bodyMinimumTextHeight(CGFloat bodyHeightPoints) {
    if (bodyHeightPoints <= 0) {
        return 1.0f;
    }
    return (float)MIN(1.0, 7.0 / bodyHeightPoints);
}

static CGFloat deriveChatBottom(PixelBuffer buffer, CGFloat scaleX, CGFloat scaleY,
                                CGFloat windowWidth, CGFloat windowHeight,
                                CGFloat chatLeft, CGFloat chatTop) {
    if (buffer.bytes == NULL || scaleX <= 0 || scaleY <= 0 ||
        windowWidth - chatLeft < 280.0 || windowHeight - chatTop < 220.0) {
        return -1.0;
    }
    NSInteger minY = (NSInteger)ceil(MAX(chatTop + 120.0, windowHeight * 0.35));
    NSInteger maxY = (NSInteger)floor(windowHeight - 70.0);
    NSInteger minX = (NSInteger)ceil(chatLeft + 20.0);
    NSInteger maxX = (NSInteger)floor(windowWidth - 20.0);
    if (minY >= maxY || minX >= maxX) {
        return -1.0;
    }

    CGFloat bestCandidate = -1.0;
    double bestScore = 0.0;
    for (NSInteger candidateY = minY; candidateY <= maxY; candidateY++) {
        NSInteger previousPixelY = (NSInteger)floor((candidateY - 1.0) * scaleY);
        NSInteger currentPixelY = (NSInteger)round(candidateY * scaleY);
        NSInteger nextPixelY = (NSInteger)ceil((candidateY + 1.0) * scaleY);
        if (previousPixelY < 0 || nextPixelY >= (NSInteger)buffer.height) {
            continue;
        }
        NSUInteger samples = 0;
        double evidence = 0.0;
        for (NSInteger x = minX; x <= maxX; x += 8) {
            NSInteger pixelX = (NSInteger)round(x * scaleX);
            if (pixelX < 0 || pixelX >= (NSInteger)buffer.width) {
                continue;
            }
            unsigned char *previous = buffer.bytes + (size_t)previousPixelY * buffer.stride +
                                      (size_t)pixelX * 4;
            unsigned char *current = buffer.bytes + (size_t)currentPixelY * buffer.stride +
                                     (size_t)pixelX * 4;
            unsigned char *next = buffer.bytes + (size_t)nextPixelY * buffer.stride +
                                  (size_t)pixelX * 4;
            int previousDifference = (abs((int)previous[0] - (int)current[0]) +
                                      abs((int)previous[1] - (int)current[1]) +
                                      abs((int)previous[2] - (int)current[2])) / 3;
            int nextDifference = (abs((int)current[0] - (int)next[0]) +
                                  abs((int)current[1] - (int)next[1]) +
                                  abs((int)current[2] - (int)next[2])) / 3;
            samples++;
            if (previousDifference >= 4) {
                evidence += 1.0;
                if (nextDifference >= 4) {
                    evidence += 0.25;
                }
            }
        }
        double score = samples == 0 ? 0.0 : evidence / (double)samples;
        if (score > bestScore) {
            bestScore = score;
            bestCandidate = candidateY;
        }
    }
    return bestScore >= 0.55 ? bestCandidate : -1.0;
}

static NSDictionary *runSelfTest(void) {
    NSUInteger failures = 0;
    BOOL outgoing = NO;
    PixelBuffer darkBubble = solidPixelBuffer(781, 150, 30, 30, 31);
    fillPixelRect(darkBubble, CGRectMake(370, 35, 100, 55), 47, 47, 48);
    fillPixelRect(darkBubble, CGRectMake(365, 43, 5, 8), 47, 47, 48);
    if (!isBubbleText(darkBubble, CGRectMake(380, 55, 55, 16), 1, 1, 300, 10, 481, &outgoing) || outgoing) {
        failures++;
    }
    free(darkBubble.bytes);

    PixelBuffer whiteBubble = solidPixelBuffer(781, 150, 245, 245, 245);
    fillPixelRect(whiteBubble, CGRectMake(370, 35, 100, 55), 255, 255, 255);
    fillPixelRect(whiteBubble, CGRectMake(365, 43, 5, 8), 255, 255, 255);
    if (!isBubbleText(whiteBubble, CGRectMake(380, 55, 55, 16), 1, 1, 300, 10, 481, &outgoing) || outgoing) {
        failures++;
    }
    free(whiteBubble.bytes);

    PixelBuffer greenBubble = solidPixelBuffer(781, 150, 245, 245, 245);
    fillPixelRect(greenBubble, CGRectMake(610, 35, 102, 55), 149, 236, 105);
    fillPixelRect(greenBubble, CGRectMake(712, 43, 5, 8), 149, 236, 105);
    if (!isBubbleText(greenBubble, CGRectMake(635, 55, 55, 16), 1, 1, 300, 10, 481, &outgoing) || !outgoing) {
        failures++;
    }
    free(greenBubble.bytes);

    PixelBuffer whiteDocument = solidPixelBuffer(781, 150, 30, 30, 31);
    fillPixelRect(whiteDocument, CGRectMake(382, 35, 120, 75), 255, 255, 255);
    if (isBubbleText(whiteDocument, CGRectMake(382, 55, 120, 16), 1, 1, 300, 10, 481, &outgoing)) {
        failures++;
    }
    free(whiteDocument.bytes);

    PixelBuffer chatBackground = solidPixelBuffer(781, 150, 30, 30, 31);
    if (isBubbleText(chatBackground, CGRectMake(380, 55, 55, 16), 1, 1, 300, 10, 481, &outgoing) || outgoing) {
        failures++;
    }
    free(chatBackground.bytes);

    PixelBuffer timestamp = solidPixelBuffer(781, 150, 30, 30, 31);
    fillPixelRect(timestamp, CGRectMake(515, 45, 50, 24), 47, 47, 48);
    if (isBubbleText(timestamp, CGRectMake(520, 50, 40, 14), 1, 1, 300, 10, 481, &outgoing)) {
        failures++;
    }
    free(timestamp.bytes);

    PixelBuffer clipped = solidPixelBuffer(781, 150, 30, 30, 31);
    fillPixelRect(clipped, CGRectMake(370, 10, 100, 40), 47, 47, 48);
    fillPixelRect(clipped, CGRectMake(365, 18, 5, 8), 47, 47, 48);
    if (isBubbleText(clipped, CGRectMake(380, 11, 55, 16), 1, 1, 300, 10, 481, &outgoing)) {
        failures++;
    }
    free(clipped.bytes);

    PixelBuffer wrappedBubble = solidPixelBuffer(781, 150, 30, 30, 31);
    fillPixelRect(wrappedBubble, CGRectMake(370, 20, 145, 100), 47, 47, 48);
    fillPixelRect(wrappedBubble, CGRectMake(365, 28, 5, 8), 47, 47, 48);
    if (!isBubbleText(wrappedBubble, CGRectMake(390, 90, 90, 16), 1, 1, 300, 10, 481, &outgoing) || outgoing) {
        failures++;
    }
    free(wrappedBubble.bytes);

    PixelBuffer retinaBubble = solidPixelBuffer(1562, 300, 30, 30, 31);
    fillPixelRect(retinaBubble, CGRectMake(740, 70, 200, 110), 47, 47, 48);
    fillPixelRect(retinaBubble, CGRectMake(730, 86, 10, 16), 47, 47, 48);
    if (!isBubbleText(retinaBubble, CGRectMake(380, 55, 55, 16), 2, 2, 300, 10, 481, &outgoing) || outgoing) {
        failures++;
    }
    free(retinaBubble.bytes);

    if (!normalizedTitleMatches(@"卧底不追高", @"卧底不追高")) {
        failures++;
    }
    if (!normalizedTitleMatches(@" 卧底不追高 (16) ", @"卧底不追高")) {
        failures++;
    }
    if (!normalizedTitleMatches(@"卧底不追高（16）", @"卧底不追高")) {
        failures++;
    }
    if (normalizedTitleMatches(@"卧底不追高的朋友", @"卧底不追高")) {
        failures++;
    }
    if (normalizedTitleMatches(@"前缀卧底不追高(16)", @"卧底不追高")) {
        failures++;
    }

    NSDictionary *exactSelection = selectTitleLine(@[
        @{@"candidates": @[@"卧底不追高"],
          @"rect": [NSValue valueWithRect:NSMakeRect(315, 20, 100, 18)]},
    ], @"卧底不追高");
    if (![exactSelection[@"text"] isEqualToString:@"卧底不追高"]) {
        failures++;
    }

    NSDictionary *conflictingSelection = selectTitleLine(@[
        @{@"candidates": @[@"另一个群", @"卧底不追高"],
          @"rect": [NSValue valueWithRect:NSMakeRect(315, 20, 100, 18)]},
    ], @"卧底不追高");
    if (conflictingSelection != nil) {
        failures++;
    }

    NSDictionary *splitSuffixSelection = selectTitleLine(@[
        @{@"candidates": @[@"卧底不追高"],
          @"rect": [NSValue valueWithRect:NSMakeRect(315, 20, 100, 18)]},
        @{@"candidates": @[@"的朋友"],
          @"rect": [NSValue valueWithRect:NSMakeRect(418, 20, 48, 18)]},
    ], @"卧底不追高");
    if (splitSuffixSelection != nil) {
        failures++;
    }

    NSDictionary *splitCountSelection = selectTitleLine(@[
        @{@"candidates": @[@"卧底不追高"],
          @"rect": [NSValue valueWithRect:NSMakeRect(315, 20, 100, 18)]},
        @{@"candidates": @[@"(16)"],
          @"rect": [NSValue valueWithRect:NSMakeRect(418, 20, 32, 18)]},
    ], @"卧底不追高");
    NSRect splitCountRect = [splitCountSelection[@"rect"] rectValue];
    if (![splitCountSelection[@"text"] isEqualToString:@"卧底不追高(16)"] ||
        NSMaxX(splitCountRect) != 450) {
        failures++;
    }

    PixelBuffer standardLayout = solidPixelBuffer(800, 200, 20, 20, 21);
    fillPixelRect(standardLayout, CGRectMake(300, 0, 500, 200), 30, 30, 31);
    CGFloat standardLeft = deriveChatLeft(standardLayout, CGRectMake(315, 20, 100, 18),
                                          1, 1, 800, 52, 180);
    if (standardLeft != 300) {
        failures++;
    }
    free(standardLayout.bytes);

    PixelBuffer widenedLayout = solidPixelBuffer(900, 200, 20, 20, 21);
    fillPixelRect(widenedLayout, CGRectMake(450, 0, 450, 200), 30, 30, 31);
    CGFloat widenedLeft = deriveChatLeft(widenedLayout, CGRectMake(465, 20, 100, 18),
                                         1, 1, 900, 52, 180);
    if (widenedLeft != 450) {
        failures++;
    }
    free(widenedLayout.bytes);

    PixelBuffer retinaWidenedLayout = solidPixelBuffer(1800, 400, 20, 20, 21);
    fillPixelRect(retinaWidenedLayout, CGRectMake(900, 0, 900, 400), 30, 30, 31);
    CGFloat retinaWidenedLeft = deriveChatLeft(retinaWidenedLayout,
                                               CGRectMake(465, 20, 100, 18),
                                               2, 2, 900, 52, 180);
    if (retinaWidenedLeft != 450) {
        failures++;
    }
    free(retinaWidenedLayout.bytes);

    PixelBuffer unsupportedLayout = solidPixelBuffer(800, 200, 30, 30, 31);
    CGFloat unsupportedLeft = deriveChatLeft(unsupportedLayout, CGRectMake(315, 20, 100, 18),
                                              1, 1, 800, 52, 180);
    if (unsupportedLeft >= 0) {
        failures++;
    }
    free(unsupportedLayout.bytes);

    if (fabs(bodyMinimumTextHeight(473) * 473.0 - 7.0) > 0.001) {
        failures++;
    }
    if (fabs(bodyMinimumTextHeight(800) * 800.0 - 7.0) > 0.001) {
        failures++;
    }

    PixelBuffer standardComposer = solidPixelBuffer(781, 668, 30, 30, 31);
    fillPixelRect(standardComposer, CGRectMake(300, 525, 481, 143), 20, 20, 21);
    if (deriveChatBottom(standardComposer, 1, 1, 781, 668, 300, 52) != 525) {
        failures++;
    }
    free(standardComposer.bytes);

    PixelBuffer tallComposer = solidPixelBuffer(781, 668, 30, 30, 31);
    fillPixelRect(tallComposer, CGRectMake(300, 430, 481, 238), 20, 20, 21);
    if (deriveChatBottom(tallComposer, 1, 1, 781, 668, 300, 52) != 430) {
        failures++;
    }
    free(tallComposer.bytes);

    PixelBuffer unsupportedComposer = solidPixelBuffer(781, 668, 30, 30, 31);
    if (deriveChatBottom(unsupportedComposer, 1, 1, 781, 668, 300, 52) >= 0) {
        failures++;
    }
    free(unsupportedComposer.bytes);

    PixelBuffer retinaComposer = solidPixelBuffer(1562, 1336, 30, 30, 31);
    fillPixelRect(retinaComposer, CGRectMake(600, 1050, 962, 286), 20, 20, 21);
    if (deriveChatBottom(retinaComposer, 2, 2, 781, 668, 300, 52) != 525) {
        failures++;
    }
    free(retinaComposer.bytes);

    return @{@"status": failures == 0 ? @"ok" : @"capture_error",
             @"tests": @28, @"failures": @(failures)};
}

static NSString *singleLine(NSString *value) {
    NSCharacterSet *lineBreaks = [NSCharacterSet newlineCharacterSet];
    NSArray<NSString *> *parts = [value componentsSeparatedByCharactersInSet:lineBreaks];
    NSString *joined = [parts componentsJoinedByString:@" "];
    return [joined stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
}

static NSArray<NSDictionary *> *recognizedBodyLines(CGImageRef image,
                                                     CGRect bodyPixels,
                                                     CGFloat chatLeft,
                                                     CGFloat chatTop,
                                                     CGFloat bodyWidthPoints,
                                                     CGFloat bodyHeightPoints,
                                                     CGFloat scaleX,
                                                     CGFloat scaleY) {
    CGImageRef cropped = CGImageCreateWithImageInRect(image, bodyPixels);
    if (cropped == nil) {
        return nil;
    }
    NSArray<VNRecognizedTextObservation *> *observations =
        recognize(cropped, nil, bodyMinimumTextHeight(bodyHeightPoints));
    CGImageRelease(cropped);
    if (observations == nil) {
        return nil;
    }

    PixelBuffer buffer = makePixelBuffer(image);
    NSMutableArray<NSDictionary *> *lines = [NSMutableArray array];
    for (VNRecognizedTextObservation *observation in observations) {
        VNRecognizedText *candidate = [[observation topCandidates:1] firstObject];
        NSString *text = singleLine(candidate.string ?: @"");
        if (text.length == 0) {
            continue;
        }
        CGRect normalized = observation.boundingBox;
        CGFloat x = chatLeft + CGRectGetMinX(normalized) * bodyWidthPoints;
        CGFloat y = chatTop + (1.0 - CGRectGetMaxY(normalized)) * bodyHeightPoints;
        CGFloat width = CGRectGetWidth(normalized) * bodyWidthPoints;
        CGFloat height = CGRectGetHeight(normalized) * bodyHeightPoints;
        CGRect rect = CGRectMake(x, y, width, height);
        BOOL outgoing = NO;
        BOOL bubble = isBubbleText(buffer, rect, scaleX, scaleY,
                                   chatLeft, chatTop, bodyWidthPoints, &outgoing);
        [lines addObject:@{
            @"text": text,
            @"x": @(x),
            @"y": @(y),
            @"width": @(width),
            @"height": @(height),
            @"confidence": @(candidate.confidence),
            @"bubble": @(bubble),
            @"outgoing": @(outgoing),
        }];
    }
    free(buffer.bytes);
    [lines sortUsingComparator:^NSComparisonResult(NSDictionary *left, NSDictionary *right) {
        double leftY = [left[@"y"] doubleValue];
        double rightY = [right[@"y"] doubleValue];
        if (fabs(leftY - rightY) > 3.0) {
            return leftY < rightY ? NSOrderedAscending : NSOrderedDescending;
        }
        return [left[@"x"] doubleValue] < [right[@"x"] doubleValue]
            ? NSOrderedAscending : NSOrderedDescending;
    }];
    return lines;
}

static NSDictionary *captureTarget(NSString *target) {
    if (!CGPreflightScreenCaptureAccess()) {
        return emptyFrame(@"permission_required");
    }
    NSDictionary *session = CFBridgingRelease(CGSessionCopyCurrentDictionary());
    NSNumber *screenLocked = session[@"CGSSessionScreenIsLocked"];
    if (session == nil || screenLocked.boolValue) {
        return emptyFrame(@"window_unavailable");
    }
    NSArray<NSRunningApplication *> *applications =
        [NSRunningApplication runningApplicationsWithBundleIdentifier:WeChatBundleIdentifier];
    if (applications.count == 0) {
        return emptyFrame(@"wechat_not_running");
    }

    CFArrayRef windowInfoRef = CGWindowListCopyWindowInfo(
        kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements,
        kCGNullWindowID);
    NSArray<NSDictionary *> *windowInfo = CFBridgingRelease(windowInfoRef);
    NSMutableSet<NSNumber *> *wechatPIDs = [NSMutableSet set];
    for (NSRunningApplication *application in applications) {
        [wechatPIDs addObject:@(application.processIdentifier)];
    }

    BOOL foundEligibleWindow = NO;
    for (NSDictionary *window in windowInfo) {
        NSNumber *ownerPID = window[(id)kCGWindowOwnerPID];
        NSNumber *layer = window[(id)kCGWindowLayer];
        if (![wechatPIDs containsObject:ownerPID] || layer.integerValue != 0) {
            continue;
        }
        CGRect bounds = CGRectZero;
        if (!CGRectMakeWithDictionaryRepresentation((__bridge CFDictionaryRef)window[(id)kCGWindowBounds], &bounds) ||
            bounds.size.width < 500 || bounds.size.height < 300) {
            continue;
        }
        foundEligibleWindow = YES;
        CGWindowID windowID = [window[(id)kCGWindowNumber] unsignedIntValue];
        CGImageRef image = CGWindowListCreateImage(
            CGRectNull, kCGWindowListOptionIncludingWindow, windowID,
            kCGWindowImageBoundsIgnoreFraming | kCGWindowImageBestResolution);
        if (image == nil || CGImageGetWidth(image) == 0 || CGImageGetHeight(image) == 0) {
            if (image != nil) {
                CGImageRelease(image);
            }
            continue;
        }

        CGFloat width = bounds.size.width;
        CGFloat height = bounds.size.height;
        CGFloat scaleX = (CGFloat)CGImageGetWidth(image) / width;
        CGFloat scaleY = (CGFloat)CGImageGetHeight(image) / height;
        CGFloat chatTop = 52.0;
        CGRect titleRect = CGRectZero;
        BOOL titleRequestSucceeded = NO;
        BOOL sawTitleText = NO;
        NSString *titleOCR = recognizedTargetTitle(image, width, chatTop, scaleX, scaleY,
                                                   target, &titleRect,
                                                   &titleRequestSucceeded, &sawTitleText);
        if (!titleRequestSucceeded) {
            CGImageRelease(image);
            return emptyFrame(@"capture_error");
        }
        if (!sawTitleText) {
            CGImageRelease(image);
            return emptyFrame(@"window_unavailable");
        }
        if (titleOCR == nil) {
            CGImageRelease(image);
            continue;
        }
        PixelBuffer layoutBuffer = makePixelBuffer(image);
        CGFloat layoutScanBottom = MAX(chatTop, height - 70.0);
        CGFloat chatLeft = deriveChatLeft(layoutBuffer, titleRect, scaleX, scaleY,
                                          width, chatTop, layoutScanBottom);
        if (chatLeft < 0) {
            free(layoutBuffer.bytes);
            CGImageRelease(image);
            return emptyFrame(@"window_unavailable");
        }
        CGFloat chatBottom = deriveChatBottom(layoutBuffer, scaleX, scaleY,
                                              width, height, chatLeft, chatTop);
        free(layoutBuffer.bytes);
        if (chatBottom <= chatTop) {
            CGImageRelease(image);
            return emptyFrame(@"window_unavailable");
        }

        CGFloat bodyWidth = width - chatLeft;
        CGFloat bodyHeight = chatBottom - chatTop;
        CGRect bodyPixels = CGRectMake(chatLeft * scaleX, chatTop * scaleY,
                                       bodyWidth * scaleX, bodyHeight * scaleY);
        NSArray<NSDictionary *> *lines = recognizedBodyLines(
            image, bodyPixels, chatLeft, chatTop, bodyWidth, bodyHeight, scaleX, scaleY);
        CGImageRelease(image);
        if (lines == nil) {
            return emptyFrame(@"capture_error");
        }
        return @{
            @"status": @"ok",
            @"windowId": @(windowID),
            @"title": titleOCR,
            @"width": @(width),
            @"height": @(height),
            @"chatLeft": @(chatLeft),
            @"chatTop": @(chatTop),
            @"chatBottom": @(chatBottom),
            @"lines": lines,
        };
    }
    return emptyFrame(foundEligibleWindow ? @"other_chat" : @"window_unavailable");
}

static NSString *argumentValue(NSArray<NSString *> *arguments, NSString *name) {
    NSUInteger index = [arguments indexOfObject:name];
    if (index == NSNotFound || index + 1 >= arguments.count) {
        return nil;
    }
    return arguments[index + 1];
}

int main(void) {
    @autoreleasepool {
        NSArray<NSString *> *arguments = [NSProcessInfo processInfo].arguments;
        if ([arguments containsObject:@"--permission"]) {
            emitJSON(@{@"status": @"ok", @"granted": @(CGPreflightScreenCaptureAccess())});
            return 0;
        }
        if ([arguments containsObject:@"--self-test"]) {
            NSDictionary *result = runSelfTest();
            emitJSON(result);
            return [result[@"failures"] unsignedIntegerValue] == 0 ? 0 : 1;
        }

        NSString *target = argumentValue(arguments, @"--target");
        if (target.length == 0) {
            emitJSON(emptyFrame(@"capture_error"));
            return 2;
        }
        if ([arguments containsObject:@"--once"]) {
            emitJSON(captureTarget(target));
            return 0;
        }

        char *line = NULL;
        size_t capacity = 0;
        while (getline(&line, &capacity, stdin) != -1) {
            NSString *command = [[NSString alloc] initWithUTF8String:line];
            command = [command stringByTrimmingCharactersInSet:
                [NSCharacterSet whitespaceAndNewlineCharacterSet]];
            if ([command isEqualToString:@"capture"]) {
                @autoreleasepool {
                    emitJSON(captureTarget(target));
                }
            }
        }
        free(line);
    }
    return 0;
}
