#import "AnalyticsBridge.h"
@import FirebaseAnalytics;

void analytics_logEvent(const char *name, const char *jsonParams) {
    if (name == NULL) return;

    NSString *eventName = [NSString stringWithUTF8String:name];
    NSDictionary *params = nil;

    if (jsonParams != NULL) {
        NSString *json = [NSString stringWithUTF8String:jsonParams];
        NSData *data = [json dataUsingEncoding:NSUTF8StringEncoding];
        NSError *error = nil;
        id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error];

        if (error == nil && [object isKindOfClass:[NSDictionary class]]) {
            params = (NSDictionary *)object;
        }
    }

    [FIRAnalytics logEventWithName:eventName parameters:params];
}
