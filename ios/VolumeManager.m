#import <AVFoundation/AVFoundation.h>
#import <VolumeManager.h>

@import MediaPlayer;
@import UIKit;

@interface CustomVolumeView : UIView
@property(nonatomic, strong) UISlider *volumeSlider;
@end

@implementation CustomVolumeView

- (instancetype)initWithFrame:(CGRect)frame {
  self = [super initWithFrame:frame];
  if (self) {
    [self setupVolumeSlider];
  }
  return self;
}

- (void)setupVolumeSlider {
  MPVolumeView *volumeView = [[MPVolumeView alloc] initWithFrame:self.bounds];
  volumeView.showsRouteButton = NO;
  for (UIView *view in [volumeView subviews]) {
    if ([view isKindOfClass:[UISlider class]]) {
      self.volumeSlider = (UISlider *)view;
      break;
    }
  }

  if (self.volumeSlider) {
    [self addSubview:self.volumeSlider];
    self.volumeSlider.autoresizingMask =
        UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
  }
}

@end

@implementation VolumeManager {
  bool hasListeners; CustomVolumeView *customVolumeView;
  AVAudioSession *audioSession;
  float previousVolume;
  NSTimer *longPressTimer;
  NSInteger volumeUpPressCount;
  NSInteger volumeDownPressCount;
  CFTimeInterval lastVolumeUpPressTime;
  CFTimeInterval lastVolumeDownPressTime;
}

- (void)dealloc {
  [self removeVolumeListener];
}

- (instancetype)init {
  self = [super init];
  if (self) {
    audioSession = [AVAudioSession sharedInstance];
    volumeUpPressCount = 0;
    volumeDownPressCount = 0;
    lastVolumeUpPressTime = 0;
    lastVolumeDownPressTime = 0;
    [self addVolumeListener];
  }

  [self initVolumeView];
  return self;
}

- (void)initVolumeView {
  customVolumeView = [[CustomVolumeView alloc] init];
  customVolumeView.transform = CGAffineTransformMakeScale(0.0, 0.0);
  for (UIView *subview in customVolumeView.subviews) {
    if ([subview isKindOfClass:[UIButton class]]) {
      subview.hidden = YES;
      break;
    }
  }
  [self showVolumeUI:YES];

  [[NSNotificationCenter defaultCenter]
      addObserver:self
         selector:@selector(applicationWillEnterForeground:)
             name:UIApplicationWillEnterForegroundNotification
           object:nil];
}

- (UIViewController *)topMostViewController {
  UIViewController *topController =
      [[UIApplication sharedApplication] keyWindow].rootViewController;
  while (topController.presentedViewController) {
    topController = topController.presentedViewController;
  }
  return topController;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[ @"RNVMEventVolume", @"VolumeKeyEvent" ];
}

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

RCT_EXPORT_MODULE(VolumeManager)

- (void)startObserving {
  hasListeners = YES;
}

- (void)stopObserving {
  hasListeners = NO;
}

- (void)showVolumeUI:(BOOL)flag {
  __weak typeof(self) weakSelf = self;
  dispatch_async(dispatch_get_main_queue(), ^{
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (strongSelf) {
      if (flag && [strongSelf->customVolumeView superview]) {
        [strongSelf->customVolumeView removeFromSuperview];
      } else if (!flag && ![strongSelf->customVolumeView superview]) {
        strongSelf->customVolumeView.frame =
            CGRectMake(0, 0, 0, 0); // Set the frame to CGRectZero
        UIViewController *topViewController =
            [strongSelf topMostViewController];
        [topViewController.view addSubview:strongSelf->customVolumeView];
      }
    }
  });
}

- (void)addVolumeListener {
  [audioSession setCategory:AVAudioSessionCategoryAmbient
                withOptions:AVAudioSessionCategoryOptionMixWithOthers |
                            AVAudioSessionCategoryOptionAllowBluetooth
                      error:nil];
  [audioSession setActive:YES error:nil];

  previousVolume = audioSession.outputVolume; // Store the initial volume
  [audioSession
      addObserver:self
       forKeyPath:@"outputVolume"
          options:NSKeyValueObservingOptionNew | NSKeyValueObservingOptionOld
          context:nil];
}

- (void)removeVolumeListener {
  [audioSession removeObserver:self forKeyPath:@"outputVolume"];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSString *, id> *)change
                       context:(void *)context {
  if (object == [AVAudioSession sharedInstance] &&
      [keyPath isEqualToString:@"outputVolume"]) {
    float newValue = [change[@"new"] floatValue];
    float newVolume = newValue;
    float oldVolume = [change[@"old"] floatValue];
    previousVolume = newVolume;
    if (hasListeners) {
      [self
          sendEventWithName:@"RNVMEventVolume"
                       body:@{@"volume" : [NSNumber numberWithFloat:newValue]}];
      if(newVolume > 0.99){
        newVolume = 0.5;
        oldVolume = 0.4;
          [self resetVolumeTo:newVolume];
      }
      if(newVolume < 0.01){
        newVolume = 0.5;
        oldVolume = 0.6;
          [self resetVolumeTo:newVolume];
      }
      if (newVolume > oldVolume) {
          [self handleVolumeKeyPress:@"up"];
      } else if (newVolume < oldVolume) {
          [self handleVolumeKeyPress:@"down"];
      }
    }
  }
}
- (void)resetVolumeTo:(float)value {
  __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
      __strong typeof(weakSelf) strongSelf = weakSelf;
      if(strongSelf){
        strongSelf->customVolumeView.volumeSlider.value = value;
        AVAudioSession *audioSession = [AVAudioSession sharedInstance];
        [audioSession setActive:YES error:nil];
      }
    });
}
- (void)handleVolumeKeyPress:(NSString *)direction {
    CFTimeInterval currentTime = CACurrentMediaTime();
    NSLog(@"direction: %@ ", direction);
    if ([direction isEqualToString:@"up"]) {
        if (currentTime - lastVolumeUpPressTime < 0.4) {
            volumeUpPressCount++;
        } else {
            volumeUpPressCount = 1; // Reset counter for single press
        }
        NSLog(@"UP EVENT: %2f, %d", (currentTime-lastVolumeUpPressTime), volumeUpPressCount);
        lastVolumeUpPressTime = currentTime;
    } else if ([direction isEqualToString:@"down"]) {
        if (currentTime - lastVolumeDownPressTime < 0.4) {
            volumeDownPressCount++;
        } else {
            volumeDownPressCount = 1; // Reset counter for single press
        }
        lastVolumeDownPressTime = currentTime;
    }
    [self startEventDetectionTimerForDirection:direction];
    if(volumeUpPressCount >= 4 || volumeDownPressCount >= 4)
    [self startLongPressTimerForDirection:direction];
}
- (void)startEventDetectionTimerForDirection:(NSString *)direction {
    [longPressTimer invalidate]; // Cancel any existing timer

    longPressTimer = [NSTimer scheduledTimerWithTimeInterval:0.5
                                                      repeats:NO
                                                        block:^(NSTimer * _Nonnull timer) {
        [self finalizeEventForDirection:direction];
    }];
}
- (void)finalizeEventForDirection:(NSString *)direction {
    NSInteger count = [direction isEqualToString:@"up"] ? volumeUpPressCount : volumeDownPressCount;
    NSString *eventType = nil;
    switch (count) {
        case 1:
            eventType = [direction isEqualToString:@"up"] ? @"volumeUpSingle" : @"volumeDownSingle";
            break;
        case 2:
            eventType = [direction isEqualToString:@"up"] ? @"volumeUpDouble" : @"volumeDownDouble";
            break;
        case 3:
            eventType = [direction isEqualToString:@"up"] ? @"volumeUpTriple" : @"volumeDownTriple";
            break;
        default:
            break;
    }
    NSLog(@"Normal Key Press Emitting event: %@ - %@", direction, eventType);
    if (eventType && hasListeners) {
        [self sendEventWithName:@"VolumeKeyEvent"
                           body:eventType];
    }
    if ([direction isEqualToString:@"up"]) {
        volumeUpPressCount = 0;
    } else if ([direction isEqualToString:@"down"]) {
        volumeDownPressCount = 0;
    }
}
- (void)startLongPressTimerForDirection:(NSString *)direction {
    [longPressTimer invalidate]; // Cancel any existing timer
    longPressTimer = [NSTimer scheduledTimerWithTimeInterval:0.8
                                                           repeats:NO
                                                             block:^(NSTimer * _Nonnull timer) {
        [self handleLongPressForDirection:direction];
    }];
}
- (void)handleLongPressForDirection:(NSString *)direction {
    [longPressTimer invalidate]; // Cancel the timer
    NSString *eventType = nil;
    if ([direction isEqualToString:@"up"]) {
        eventType = @"volumeUpHold";
    } else if ([direction isEqualToString:@"down"]) {
        eventType = @"volumeDownHold";
    }
    NSLog(@"Emitting long press event: %@ - %d", direction, @(hasListeners));
    if (hasListeners) {
        [self sendEventWithName:@"VolumeKeyEvent"
                           body:eventType];
    }
    if ([direction isEqualToString:@"up"]) {
        volumeUpPressCount = 0;
    } else if ([direction isEqualToString:@"down"]) {
        volumeDownPressCount = 0;
    }
}
RCT_EXPORT_METHOD(showNativeVolumeUI : (NSDictionary *)showNativeVolumeUI) {
  __weak typeof(self) weakSelf = self;
  dispatch_async(dispatch_get_main_queue(), ^{
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (strongSelf) {
      id enabled = [showNativeVolumeUI objectForKey:@"enabled"];
      [strongSelf showVolumeUI:(enabled != nil && [enabled boolValue])];
    }
  });
}

RCT_EXPORT_METHOD(setVolume : (float)val config : (NSDictionary *)config) {
  __weak typeof(self) weakSelf = self;

  dispatch_async(dispatch_get_main_queue(), ^{
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (strongSelf) {
      id showUI = [config objectForKey:@"showUI"];
      [strongSelf showVolumeUI:(showUI != nil && [showUI boolValue])];
      strongSelf->customVolumeView.volumeSlider.value = val;
    }
  });
}

RCT_EXPORT_METHOD(getVolume
                  : (RCTPromiseResolveBlock)resolve rejecter
                  : (RCTPromiseRejectBlock)reject) {
  __weak typeof(self) weakSelf = self;
  dispatch_async(dispatch_get_main_queue(), ^{
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (strongSelf) {
      NSNumber *volumeNumber = [NSNumber
          numberWithFloat:[strongSelf->customVolumeView.volumeSlider value]];
      NSDictionary *volumeDictionary = @{@"volume" : volumeNumber};
      resolve(volumeDictionary);
    }
  });
}

RCT_EXTERN_METHOD(setMuteListenerInterval : (nonnull NSNumber *)newInterval)

RCT_EXPORT_METHOD(enable : (BOOL)enabled async : (BOOL)async) {
  if (async) {
    dispatch_async(
        dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
          AVAudioSession *session = [AVAudioSession sharedInstance];
          [session setCategory:AVAudioSessionCategoryAmbient error:nil];
          [session setActive:enabled
                 withOptions:
                     AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                       error:nil];
        });
  } else {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    [session setCategory:AVAudioSessionCategoryAmbient error:nil];
    [session setActive:enabled
           withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                 error:nil];
  }
}

RCT_EXPORT_METHOD(setActive : (BOOL)active async : (BOOL)async) {
  if (async) {
    dispatch_async(
        dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
          AVAudioSession *session = [AVAudioSession sharedInstance];
          [session setActive:active
                 withOptions:
                     AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                       error:nil];
        });
  } else {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    [session setActive:active
           withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                 error:nil];
  }
}

RCT_EXPORT_METHOD(setMode : (NSString *)modeName) {
  AVAudioSession *session = [AVAudioSession sharedInstance];
  NSString *mode = nil;

  if ([modeName isEqual:@"Default"]) {
    mode = AVAudioSessionModeDefault;
  } else if ([modeName isEqual:@"VoiceChat"]) {
    mode = AVAudioSessionModeVoiceChat;
  } else if ([modeName isEqual:@"VideoChat"]) {
    mode = AVAudioSessionModeVideoChat;
  } else if ([modeName isEqual:@"GameChat"]) {
    mode = AVAudioSessionModeGameChat;
  } else if ([modeName isEqual:@"VideoRecording"]) {
    mode = AVAudioSessionModeVideoRecording;
  } else if ([modeName isEqual:@"Measurement"]) {
    mode = AVAudioSessionModeMeasurement;
  } else if ([modeName isEqual:@"MoviePlayback"]) {
    mode = AVAudioSessionModeMoviePlayback;
  } else if ([modeName isEqual:@"SpokenAudio"]) {
    mode = AVAudioSessionModeSpokenAudio;
  }

  if (mode) {
    [session setMode:mode error:nil];
  }
}

RCT_EXPORT_METHOD(setCategory
                  : (NSString *)categoryName mixWithOthers
                  : (BOOL)mixWithOthers) {
  AVAudioSession *session = [AVAudioSession sharedInstance];
  NSString *category = nil;

  if ([categoryName isEqual:@"Ambient"]) {
    category = AVAudioSessionCategoryAmbient;
  } else if ([categoryName isEqual:@"SoloAmbient"]) {
    category = AVAudioSessionCategorySoloAmbient;
  } else if ([categoryName isEqual:@"Playback"]) {
    category = AVAudioSessionCategoryPlayback;
  } else if ([categoryName isEqual:@"Record"]) {
    category = AVAudioSessionCategoryRecord;
  } else if ([categoryName isEqual:@"PlayAndRecord"]) {
    category = AVAudioSessionCategoryPlayAndRecord;
  } else if ([categoryName isEqual:@"MultiRoute"]) {
    category = AVAudioSessionCategoryMultiRoute;
  }

  if (category) {
    if (mixWithOthers) {
      [session setCategory:category
               withOptions:AVAudioSessionCategoryOptionMixWithOthers |
                           AVAudioSessionCategoryOptionAllowBluetooth
                     error:nil];
    } else {
      [session setCategory:category error:nil];
    }
  }
}

RCT_EXPORT_METHOD(enableInSilenceMode : (BOOL)enabled) {
  dispatch_async(
      dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        AVAudioSession *session = [AVAudioSession sharedInstance];
        [session setCategory:AVAudioSessionCategoryPlayback error:nil];
        [session setActive:enabled error:nil];
      });
}

- (void)applicationWillEnterForeground:(NSNotification *)notification {
  if (hasListeners) {
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    [audioSession setActive:YES error:nil];
  }
}

@end
