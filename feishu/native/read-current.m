#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#include <unistd.h>

static id attr(AXUIElementRef element, CFStringRef name) {
    CFTypeRef value = NULL;
    if (AXUIElementCopyAttributeValue(element, name, &value) != kAXErrorSuccess) return nil;
    return CFBridgingRelease(value);
}
static NSString *str(id value) { return [value isKindOfClass:NSString.class] ? value : @""; }
static NSArray *children(AXUIElementRef e) {
    id value = attr(e, kAXChildrenAttribute);
    return [value isKindOfClass:NSArray.class] ? value : @[];
}
static void findAreas(AXUIElementRef e, NSString *name, NSUInteger depth, NSUInteger *count, NSMutableArray *matches) {
    if (depth > 24 || ++*count > 10000) return;
    NSString *role = str(attr(e, kAXRoleAttribute));
    if ([role isEqualToString:@"AXWebArea"]) {
        NSString *title = str(attr(e, kAXTitleAttribute));
        NSString *description = str(attr(e, kAXDescriptionAttribute));
        if ([title isEqualToString:name] || [description isEqualToString:name])
            [matches addObject:(__bridge id)e];
        return;
    }
    for (id child in children(e)) findAreas((__bridge AXUIElementRef)child, name, depth + 1, count, matches);
}
static NSString *firstText(AXUIElementRef e, NSUInteger depth) {
    if (depth > 35) return @"";
    if ([str(attr(e,kAXRoleAttribute)) isEqualToString:@"AXStaticText"])
        return str(attr(e,kAXValueAttribute));
    for (id child in children(e)) {
        NSString *value = firstText((__bridge AXUIElementRef)child,depth+1);
        if (value.length) return value;
    }
    return @"";
}
static NSDictionary *node(AXUIElementRef e, NSUInteger depth, NSUInteger *count, BOOL *truncated) {
    if (depth > 35 || ++*count > 15000) { *truncated = YES; return @{}; }
    NSMutableDictionary *out = [NSMutableDictionary dictionary];
    NSString *role = str(attr(e, kAXRoleAttribute));
    out[@"role"] = role;
    CFArrayRef actionNames = NULL;
    if (AXUIElementCopyActionNames(e,&actionNames)==kAXErrorSuccess) out[@"actions"] = CFBridgingRelease(actionNames);
    for (NSString *name in @[@"AXTitle", @"AXValue", @"AXDescription", @"AXIdentifier", @"AXDOMIdentifier"]) {
        NSString *value = str(attr(e, (__bridge CFStringRef)name));
        if (value.length && ![role isEqualToString:@"AXTextArea"] && ![role isEqualToString:@"AXTextField"]) {
            NSString *key = [@{@"AXTitle":@"title",@"AXValue":@"value",@"AXDescription":@"description",@"AXIdentifier":@"identifier",@"AXDOMIdentifier":@"domId"} objectForKey:name];
            if ([role isEqualToString:@"AXImage"] && ([value hasPrefix:@"/image"] || [value hasPrefix:@"http"])) value = @"[图片]";
            out[key] = value;
        }
    }
    NSMutableArray *list = [NSMutableArray array];
    if (![role isEqualToString:@"AXTextArea"] && ![role isEqualToString:@"AXTextField"]) {
        for (id child in children(e)) {
            [list addObject:node((__bridge AXUIElementRef)child, depth+1, count, truncated)];
            if (*truncated) break;
        }
    }
    out[@"children"] = list;
    return out;
}
static AXUIElementRef area(id window, NSString *name) {
    if (!window || CFGetTypeID((__bridge CFTypeRef)window)!=AXUIElementGetTypeID()) return NULL;
    NSUInteger count=0;NSMutableArray *matches=[NSMutableArray array];
    findAreas((__bridge AXUIElementRef)window,name,0,&count,matches);
    return matches.count==1 ? (AXUIElementRef)CFRetain((__bridge AXUIElementRef)matches[0]) : NULL;
}
// Paths refer only to title labels in the freshly observed messenger tree.
// A stale/reordered list fails closed before any activation.
static AXUIElementRef titleAtPath(AXUIElementRef root, NSString *path, NSString *expected) {
    NSArray *parts=[path componentsSeparatedByString:@"_"];
    if(parts.count<2 || ![parts[0] isEqualToString:@"r"] || parts.count>36)return NULL;
    id current=(__bridge id)root;
    NSCharacterSet *nonDigits=[[NSCharacterSet decimalDigitCharacterSet] invertedSet];
    for(NSUInteger i=1;i<parts.count;i++) {
        NSString *part=parts[i];
        if(!part.length || [part rangeOfCharacterFromSet:nonDigits].location!=NSNotFound)return NULL;
        NSArray *cs=children((__bridge AXUIElementRef)current);
        NSUInteger index=part.integerValue;
        if(index>=cs.count)return NULL;
        current=cs[index];
    }
    AXUIElementRef label=(__bridge AXUIElementRef)current;
    if(![str(attr(label,kAXRoleAttribute)) isEqualToString:@"AXStaticText"] ||
       ![str(attr(label,kAXValueAttribute)) isEqualToString:expected])return NULL;
    return (AXUIElementRef)CFRetain(label);
}
static BOOL selectTitle(AXUIElementRef label, pid_t pid) {
    if(AXUIElementPerformAction(label,kAXPressAction)==kAXErrorSuccess)return YES;
    AXUIElementPerformAction(label,CFSTR("AXScrollToVisible"));
    id position=attr(label,kAXPositionAttribute),size=attr(label,kAXSizeAttribute);
    CGPoint p;CGSize s;
    if(!position || !size || CFGetTypeID((__bridge CFTypeRef)position)!=AXValueGetTypeID() ||
       CFGetTypeID((__bridge CFTypeRef)size)!=AXValueGetTypeID() ||
       !AXValueGetValue((__bridge AXValueRef)position,kAXValueCGPointType,&p) ||
       !AXValueGetValue((__bridge AXValueRef)size,kAXValueCGSizeType,&s) || s.width<=0 || s.height<=0)return NO;
    p.x+=MIN(s.width/2,40);p.y+=s.height/2;
    // Target only Lark's process, without a global desktop click or keyboard input.
    CGEventRef down=CGEventCreateMouseEvent(NULL,kCGEventLeftMouseDown,p,kCGMouseButtonLeft);
    CGEventRef up=CGEventCreateMouseEvent(NULL,kCGEventLeftMouseUp,p,kCGMouseButtonLeft);
    if(!down || !up){if(down)CFRelease(down);if(up)CFRelease(up);return NO;}
    CGEventSetIntegerValueField(down,kCGMouseEventClickState,1);
    CGEventSetIntegerValueField(up,kCGMouseEventClickState,1);
    CGEventPostToPid(pid,down);CGEventPostToPid(pid,up);
    CFRelease(down);CFRelease(up);return YES;
}
static NSDictionary *capture(id window, NSString *expected) {
    AXUIElementRef chat=area(window,@"messenger-chat");
    if(!chat)return @{@"status":@"no_chat"};
    NSString *before=firstText(chat,0);
    if(expected && ![before isEqualToString:expected]) {CFRelease(chat);return @{@"status":@"not_found"};}
    NSUInteger count=0;BOOL truncated=NO;
    NSDictionary *root=node(chat,0,&count,&truncated);
    NSString *after=firstText(chat,0);CFRelease(chat);
    if(!before.length || ![before isEqualToString:after])return @{@"status":@"no_chat"};
    return truncated ? @{@"status":@"error"} : @{@"status":@"ok",@"root":root};
}
static void messageIds(NSDictionary *root, NSMutableSet *ids) {
    NSString *identifier=root[@"domId"];
    if(identifier.length>=15 && identifier.length<=22 &&
       [identifier rangeOfCharacterFromSet:[[NSCharacterSet decimalDigitCharacterSet] invertedSet]].location==NSNotFound)
        [ids addObject:identifier];
    for(NSDictionary *child in root[@"children"])messageIds(child,ids);
}
static NSDictionary *selectAndCapture(id window, AXUIElementRef sidebar, NSString *identifier, NSString *expected, pid_t pid) {
    AXUIElementRef label=titleAtPath(sidebar,identifier,expected);
    if(!label)return @{@"status":@"not_found",@"reason":@"stale_list"};
    AXUIElementRef current=area(window,@"messenger-chat");
    NSString *priorTitle=current ? firstText(current,0) : @"";
    if(current)CFRelease(current);
    NSDictionary *prior=priorTitle.length ? capture(window,priorTitle) : nil;
    if(priorTitle.length && ![prior[@"status"] isEqualToString:@"ok"]){CFRelease(label);return @{@"status":@"not_found",@"reason":@"stale_list"};}
    if([priorTitle isEqualToString:expected]){CFRelease(label);return @{@"status":@"ambiguous"};}
    NSMutableSet *oldIds=[NSMutableSet set];
    if(prior[@"root"])messageIds(prior[@"root"],oldIds);
    BOOL selected=selectTitle(label,pid);CFRelease(label);
    if(!selected)return @{@"status":@"not_found",@"reason":@"activation_failed"};
    NSDictionary *result=@{@"status":@"not_found",@"reason":@"switch_timeout"};
    for(int attempt=0;attempt<25;attempt++) {
        usleep(200000);
        NSDictionary *candidate=capture(window,expected);
        if(![candidate[@"status"] isEqualToString:@"ok"])continue;
        NSMutableSet *newIds=[NSMutableSet set];messageIds(candidate[@"root"],newIds);
        if(!newIds.count)continue;
        // Message ids are conversation-specific. Reject lingering old DOM rows
        // even when the new header has already rendered.
        if([newIds intersectsSet:oldIds])continue;
        return candidate;
    }
    return result;
}
int main(int argc,const char *argv[]) {
    @autoreleasepool {
        NSString *action=argc>1 ? @(argv[1]) : @"capture";
        NSDictionary *result;
        if (!AXIsProcessTrusted()) result = @{@"status":@"permission_required"};
        else {
            NSRunningApplication *target = nil;
            for (NSRunningApplication *app in NSWorkspace.sharedWorkspace.runningApplications)
                if ([app.bundleURL.path isEqualToString:@"/Applications/Lark.app"]) {target = app; break;}
            if (!target) result = @{@"status":@"not_running"};
            else {
                AXUIElementRef app=AXUIElementCreateApplication(target.processIdentifier);
                AXUIElementSetMessagingTimeout(app,1.0);
                id window=attr(app,kAXFocusedWindowAttribute) ?: attr(app,kAXMainWindowAttribute);
                if([action isEqualToString:@"capture"])result=capture(window,nil);
                else if([action isEqualToString:@"list"] || ([action isEqualToString:@"select"] && argc==4)) {
                    AXUIElementRef sidebar=area(window,@"messenger");
                    if(!sidebar)result=@{@"status":@"no_chat"};
                    else if([action isEqualToString:@"list"]) {
                        NSUInteger count=0;BOOL truncated=NO;
                        NSDictionary *root=node(sidebar,0,&count,&truncated);
                        result=truncated ? @{@"status":@"error"} : @{@"status":@"ok",@"root":root};
                    } else {
                        NSString *identifier=@(argv[2]),*expected=@(argv[3]);
                        result=selectAndCapture(window,sidebar,identifier,expected,target.processIdentifier);
                    }
                    if(sidebar)CFRelease(sidebar);
                } else result=@{@"status":@"error"};
                CFRelease(app);
            }
        }
        NSData *data=[NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
        fwrite(data.bytes,1,data.length,stdout);fputc('\n',stdout);
    }
    return 0;
}
