// Lecture en cours du Mac (« À l'écoute » du Centre de contrôle) par MediaRemote, cadre privé d'Apple. Depuis
// macOS 15.4, seules les applis d'Apple y ont accès : /usr/bin/perl en fait partie, alors cette bibliothèque est
// chargée par perl (voir src/mac/media.ts) et fait son travail dès le chargement. Une action par lancement, dans
// NP_ACTION : `get` (JSON de ce qui joue), `art` (pochette écrite dans NP_ARG), `play-pause`, `next`, `previous`,
// `seek` (NP_ARG en secondes). Compilée par `npm run build:nowplaying` en src/mac/NowPlaying.dylib, gardée dans le dépôt.
#import <AppKit/AppKit.h>
#include <dlfcn.h>
#include <objc/message.h>

typedef void (*GetInfoFn)(dispatch_queue_t, void (^)(NSDictionary *));
typedef Boolean (*SendCommandFn)(int, NSDictionary *);
typedef void (*SetElapsedFn)(double);

static void finish(NSDictionary *result) {
  NSData *json = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
  fwrite(json.bytes, 1, json.length, stdout);
  fputc('\n', stdout);
  exit(0);
}

static id orNull(id value) { return value ?: [NSNull null]; }

// MRNowPlayingRequest répond tout de suite, sans file d'attente ; MRMediaRemoteGetNowPlayingInfo, lui, peut ne
// jamais rappeler (Opera en lecture).
static Class request(void) { return NSClassFromString(@"MRNowPlayingRequest"); }

static id classCall(const char *selector) {
  Class c = request();
  SEL s = sel_registerName(selector);
  return [c respondsToSelector:s] ? ((id (*)(id, SEL))objc_msgSend)(c, s) : nil;
}

static BOOL localIsPlaying(void) {
  Class c = request();
  SEL s = sel_registerName("localIsPlaying");
  return [c respondsToSelector:s] && ((BOOL (*)(id, SEL))objc_msgSend)(c, s);
}

static id valueAt(id object, NSString *keyPath) {
  @try {
    return [object valueForKeyPath:keyPath];
  } @catch (NSException *e) {
    return nil;
  }
}

static void get(void) {
  NSDictionary *info = valueAt(classCall("localNowPlayingItem"), @"nowPlayingInfo");
  if (![info isKindOfClass:[NSDictionary class]] || !info.count) finish(@{});
  id player = classCall("localNowPlayingPlayerPath");
  NSDate *at = info[@"kMRMediaRemoteNowPlayingInfoTimestamp"];
  finish(@{
    @"bundle" : orNull(valueAt(player, @"client.bundleIdentifier")),
    @"name" : orNull(valueAt(player, @"client.displayName")),
    @"title" : orNull(info[@"kMRMediaRemoteNowPlayingInfoTitle"]),
    @"artist" : orNull(info[@"kMRMediaRemoteNowPlayingInfoArtist"]),
    @"album" : orNull(info[@"kMRMediaRemoteNowPlayingInfoAlbum"]),
    @"duration" : orNull(info[@"kMRMediaRemoteNowPlayingInfoDuration"]),
    @"elapsed" : orNull(info[@"kMRMediaRemoteNowPlayingInfoElapsedTime"]),
    @"rate" : orNull(info[@"kMRMediaRemoteNowPlayingInfoPlaybackRate"]),
    @"playing" : @(localIsPlaying()),
    @"timestampMs" : [at isKindOfClass:[NSDate class]] ? @(at.timeIntervalSince1970 * 1000) : [NSNull null],
    @"hasArt" : @(info[@"kMRMediaRemoteNowPlayingInfoArtworkMIMEType"] != nil),
  });
}

// La pochette n'est pas gardée par MediaRemote : il la demande à l'appli, qui peut ne pas répondre.
static void art(void *mr, NSString *path) {
  ((GetInfoFn)dlsym(mr, "MRMediaRemoteGetNowPlayingInfo"))(dispatch_get_main_queue(), ^(NSDictionary *info) {
    NSData *data = info[@"kMRMediaRemoteNowPlayingInfoArtworkData"];
    finish(@{@"ok" : @(data.length > 0 && [data writeToFile:path atomically:YES])});
  });
  CFRunLoopRunInMode(kCFRunLoopDefaultMode, 1.5, false);
  finish(@{@"ok" : @NO});
}

__attribute__((constructor)) static void run(void) {
  const char *rawAction = getenv("NP_ACTION");
  if (!rawAction) return;
  NSString *action = @(rawAction);
  NSString *arg = getenv("NP_ARG") ? @(getenv("NP_ARG")) : @"";
  void *mr = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY);
  if (!mr) finish(@{@"error" : @"MediaRemote absent"});

  if ([action isEqualToString:@"get"]) get();
  if ([action isEqualToString:@"art"]) art(mr, arg);
  if ([action isEqualToString:@"seek"]) {
    ((SetElapsedFn)dlsym(mr, "MRMediaRemoteSetElapsedTime"))(arg.doubleValue);
    finish(@{@"ok" : @YES});
  }
  // Commandes MediaRemote : 0 = lecture, 1 = pause, 4 = suivant, 5 = précédent. Lecture/pause décidée ici :
  // des navigateurs (Opera) ignorent la commande « basculer » (2).
  NSDictionary *commands = @{@"next" : @4, @"previous" : @5};
  NSNumber *command = [action isEqualToString:@"play-pause"] ? @(localIsPlaying() ? 1 : 0) : commands[action];
  if (!command) finish(@{@"error" : @"action inconnue"});
  Boolean sent = ((SendCommandFn)dlsym(mr, "MRMediaRemoteSendCommand"))(command.intValue, nil);
  finish(@{@"ok" : @(sent)});
}
