package com.instabug.flutter;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.instabug.apm.APM;
import com.instabug.apm.model.ExecutionTrace;
import com.instabug.apm.networking.APMNetworkLogger;
import com.instabug.bug.BugReporting;
import com.instabug.bug.invocation.Option;
import com.instabug.chat.Replies;
import com.instabug.crash.CrashReporting;
import com.instabug.featuresrequest.FeatureRequests;
import com.instabug.library.Feature;
import com.instabug.library.Instabug;
import com.instabug.library.InstabugColorTheme;
import com.instabug.library.InstabugCustomTextPlaceHolder;
import com.instabug.library.OnSdkDismissCallback;
import com.instabug.library.extendedbugreport.ExtendedBugReport;
import com.instabug.library.invocation.InstabugInvocationEvent;
import com.instabug.library.invocation.OnInvokeCallback;
import com.instabug.library.invocation.util.InstabugFloatingButtonEdge;
import com.instabug.library.invocation.util.InstabugVideoRecordingButtonPosition;
import com.instabug.library.logging.InstabugLog;
import com.instabug.library.model.NetworkLog;
import com.instabug.library.ui.onboarding.WelcomeMessage;
import com.instabug.library.visualusersteps.State;
import com.instabug.survey.callbacks.*;
import com.instabug.survey.Survey;
import com.instabug.survey.Surveys;
import com.instabug.apm.APM;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.instabug.library.Platform;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.reactivex.annotations.Nullable;

/**
 * InstabugFlutterPlugin
 */
public class InstabugFlutterPlugin implements MethodCallHandler, FlutterPlugin {

    final public static String INVOCATION_EVENT_NONE = "InvocationEvent.none";
    final public static String INVOCATION_EVENT_SCREENSHOT = "InvocationEvent.screenshot";
    final public static String INVOCATION_EVENT_TWO_FINGER_SWIPE_LEFT = "InvocationEvent.twoFingersSwipeLeft";
    final public static String INVOCATION_EVENT_FLOATING_BUTTON = "InvocationEvent.floatingButton";
    final public static String INVOCATION_EVENT_SHAKE = "InvocationEvent.shake";

    private InstabugCustomTextPlaceHolder placeHolder = new InstabugCustomTextPlaceHolder();
    HashMap<String, ExecutionTrace> traces = new HashMap<String, ExecutionTrace>();

    private static Context context;
    static MethodChannel channel;

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        register(registrar.context().getApplicationContext(), registrar.messenger());
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        register(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        context = null;
    }

    private static void register(Context applicationContext, BinaryMessenger messenger){
        context = applicationContext;
        channel = new MethodChannel(messenger, "instabug_flutter");
        channel.setMethodCallHandler(new InstabugFlutterPlugin());
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        Method[] methods = this.getClass().getMethods();
        boolean isImplemented = false;
        String callMethod = call.method;
        if (callMethod.contains(":")) {
            callMethod = call.method.substring(0, call.method.indexOf(":"));
        }
        for (Method method : methods) {
            if (callMethod.equals(method.getName())) {
                isImplemented = true;
                ArrayList<Object> tempParamValues = new ArrayList<>();
                if (call.arguments != null) {
                    tempParamValues = (ArrayList<Object>) call.arguments;
                }
                Object[] paramValues = tempParamValues.toArray();
                try {
                    Object returnVal = method.invoke(this, paramValues);
                    result.success(returnVal);
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                    result.notImplemented();
                }
            }
        }
        if (!isImplemented) {
            result.notImplemented();
        }
    }

    private void setCurrentPlatform() {
        try {
            Method method = getMethod(Class.forName("com.instabug.library.Instabug"), "setCurrentPlatform", int.class);
            if (method != null) {
                Log.i("IB-CP-Bridge", "invoking setCurrentPlatform with platform: " + Platform.FLUTTER);
                method.invoke(null, Platform.FLUTTER);
            } else {
                Log.e("IB-CP-Bridge", "setCurrentPlatform was not found by reflection");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * starts the SDK
     *
     * @param token            token The token that identifies the app, you can find
     *                         it on your dashboard.
     * @param invocationEvents invocationEvents The events that invoke the SDK's UI.
     */
    public void startWithToken(String token, ArrayList<String> invocationEvents) {
        setCurrentPlatform();
        InstabugInvocationEvent[] invocationEventsArray = new InstabugInvocationEvent[invocationEvents.size()];
        for (int i = 0; i < invocationEvents.size(); i++) {
            String key = invocationEvents.get(i);
            invocationEventsArray[i] = ArgsRegistry.getDeserializedValue(key);
        }

        final Application application = (Application) context;
        new Instabug.Builder(application, token)
                .setInvocationEvents(invocationEventsArray)
                .build();

        enableScreenShotByMediaProjection(true);
    }

    /**
     * Shows the welcome message in a specific mode.
     *
     * @param welcomeMessageMode An enum to set the welcome message mode to live, or
     *                           beta.
     */
    public void showWelcomeMessageWithMode(String welcomeMessageMode) {
        WelcomeMessage.State resolvedWelcomeMessageMode = ArgsRegistry.getDeserializedValue(welcomeMessageMode);
        Instabug.showWelcomeMessage(resolvedWelcomeMessageMode);
    }

    /**
     * Set the user identity.
     *
     * @param userName  Username.
     * @param userEmail User's default email
     */
    public void identifyUserWithEmail(String userEmail, String userName) {
        Instabug.identifyUser(userName, userEmail);
    }

    /**
     * Sets the default value of the user's email to null and show email field and
     * remove user name from all reports It also reset the chats on device and
     * removes user attributes, user data and completed surveys.
     */
    public void logOut() {
        Instabug.logoutUser();
    }

    /**
     * Change Locale of Instabug UI elements(defaults to English)
     *
     * @param instabugLocale
     */
    public void setLocale(String instabugLocale) {
        Locale resolvedLocale = ArgsRegistry.getDeserializedValue(instabugLocale);
        Instabug.setLocale(resolvedLocale);
    }

    /**
     * Appends a log message to Instabug internal log These logs are then sent along
     * the next uploaded report. All log messages are timestamped Note: logs passed
     * to this method are NOT printed to Logcat
     *
     * @param message the message
     */
    public void logVerbose(String message) {
        InstabugLog.v(message);
    }

    /**
     * Appends a log message to Instabug internal log These logs are then sent along
     * the next uploaded report. All log messages are timestamped Note: logs passed
     * to this method are NOT printed to Logcat
     *
     * @param message the message
     */
    public void logDebug(String message) {
        InstabugLog.d(message);
    }

    /**
     * Appends a log message to Instabug internal log These logs are then sent along
     * the next uploaded report. All log messages are timestamped Note: logs passed
     * to this method are NOT printed to Logcat
     *
     * @param message the message
     */
    public void logInfo(String message) {
        InstabugLog.i(message);
    }

    /**
     * Appends a log message to Instabug internal log These logs are then sent along
     * the next uploaded report. All log messages are timestamped Note: logs passed
     * to this method are NOT printed to Logcat
     *
     * @param message the message
     */
    public void logError(String message) {
        InstabugLog.e(message);
    }

    /**
     * Appends a log message to Instabug internal log These logs are then sent along
     * the next uploaded report. All log messages are timestamped Note: logs passed
     * to this method are NOT printed to Logcat
     *
     * @param message the message
     */
    public void logWarn(String message) {
        InstabugLog.w(message);
    }

    /**
     * Clears Instabug internal log
     */
    public void clearAllLogs() {
        InstabugLog.clearLogs();
    }

    /**
     * Sets the color theme of the SDK's whole UI.
     *
     * @param colorTheme an InstabugColorTheme to set the SDK's UI to.
     */
    public void setColorTheme(String colorTheme) {
        InstabugColorTheme resolvedTheme = ArgsRegistry.getDeserializedValue(colorTheme);
        if (resolvedTheme != null) {
            Instabug.setColorTheme(resolvedTheme);
        }
    }

    /**
     * Sets the position of Instabug floating button on the screen.
     * 
     * @param floatingButtonEdge    left or right edge of the screen.
     * @param floatingButtonOffset  offset for the position on the y-axis.
     */
    public void setFloatingButtonEdge(String floatingButtonEdge, int floatingButtonOffset) {
        InstabugFloatingButtonEdge resolvedFloatingButtonEdge = ArgsRegistry.getDeserializedValue(floatingButtonEdge);
        BugReporting.setFloatingButtonEdge(resolvedFloatingButtonEdge);
        BugReporting.setFloatingButtonOffset(floatingButtonOffset);
    }

    /**
     * Sets the position of the video recording button when using the screen recording attachment functionality.
     *
     * @param videoRecordingButtonPosition position of the video recording floating button on the screen.
     */
    public void setVideoRecordingFloatingButtonPosition(String videoRecordingButtonPosition) {
        InstabugVideoRecordingButtonPosition resolvedVideoRecordingButtonPosition = ArgsRegistry.getDeserializedValue(videoRecordingButtonPosition);
        BugReporting.setVideoRecordingFloatingButtonPosition(resolvedVideoRecordingButtonPosition);
    }

    /**
     * Appends a set of tags to previously added tags of reported feedback, bug or
     * crash.
     *
     * @param tags An array of tags to append to current tags.
     */
    public void appendTags(ArrayList<String> tags) {
        Instabug.addTags(tags.toArray(new String[0]));
    }

    /**
     * Manually removes all tags of reported feedback, bug or crash.
     */
    public void resetTags() {
        Instabug.resetTags();
    }

    /**
     * Gets all tags of reported feedback, bug or crash.
     *
     * @return An array of tags.
     */
    public ArrayList<String> getTags() {
        return Instabug.getTags();
    }

    /**
     * Adds experiments to the next report.
     *
     * @param experiments An array of experiments to add.
     */
    public void addExperiments(ArrayList<String> experiments) {
        Instabug.addExperiments(experiments);
    }

    /**
     * Removes certain experiments from the next report.
     *
     * @param experiments An array of experiments to remove.
     */
    public void removeExperiments(ArrayList<String> experiments) {
        Instabug.removeExperiments(experiments);
    }

    /**
     * Clears all experiments from the next report.
     */
    public void clearAllExperiments() {
        Instabug.clearAllExperiments();
    }

    /**
     * Set custom user attributes that are going to be sent with each feedback, bug
     * or crash.
     *
     * @param value User attribute value.
     * @param key   User attribute key.
     */
    public void setUserAttribute(String value, String key) {
        Instabug.setUserAttribute(key, value);
    }

    /**
     * Removes a given key and its associated value from user attributes. Does
     * nothing if a key does not exist.
     *
     * @param key The key to remove.
     */
    public void removeUserAttributeForKey(String key) {
        Instabug.removeUserAttribute(key);
    }

    /**
     * Returns the user attribute associated with a given key.
     * 
     * @param key The key for which to return the corresponding value.
     * @return The value associated with aKey, or null if no value is associated
     *         with aKey.
     */
    public String getUserAttributeForKey(String key) {
        return Instabug.getUserAttribute(key);
    }

    /**
     * Returns all user attributes.
     * 
     * @return A new HashMap containing all the currently set user attributes, or an
     *         empty HashMap if no user attributes have been set.
     */
    public HashMap<String, String> getUserAttributes() {
        return Instabug.getAllUserAttributes();
    }

    /**
     * invoke sdk manually
     */
    public void show() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Instabug.show();
            }
        });
    }

    /**
     * Logs a user event that happens through the lifecycle of the application.
     * Logged user events are going to be sent with each report, as well as at the
     * end of a session.
     *
     * @param name Event name.
     */
    public void logUserEventWithName(String name) {
        Instabug.logUserEvent(name);
    }

    /**
     * Overrides any of the strings shown in the SDK with custom ones.
     * 
     * @param value            String value to override the default one.
     * @param forStringWithKey Key of string to override.
     */
    public void setValue(String value, String forStringWithKey) {
        InstabugCustomTextPlaceHolder.Key key = ArgsRegistry.getDeserializedValue(forStringWithKey);
        placeHolder.set(key, value);
        Instabug.setCustomTextPlaceHolders(placeHolder);
    }

    /**
     * Enables taking screenshots by media projection.
     */
    @VisibleForTesting
    public static void enableScreenShotByMediaProjection(boolean isScreenshotByMediaProjectionEnabled) {
        BugReporting.setScreenshotByMediaProjectionEnabled(isScreenshotByMediaProjectionEnabled);
    }

    /**
     * Gets the private method that matches the class, method name and parameter
     * types given and making it accessible. For private use only.
     * 
     * @param clazz         the class the method is in
     * @param methodName    the method name
     * @param parameterType list of the parameter types of the method
     * @return the method that matches the class, method name and param types given
     */
    public static Method getMethod(Class clazz, String methodName, Class... parameterType) {
        final Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().equals(methodName) && method.getParameterTypes().length == parameterType.length) {
                for (int i = 0; i < parameterType.length; i++) {
                    if (method.getParameterTypes()[i] == parameterType[i]) {
                        if (i == method.getParameterTypes().length - 1) {
                            method.setAccessible(true);
                            return method;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Enable/disable session profiler
     *
     * @param sessionProfilerEnabled desired state of the session profiler feature
     */
    public void setSessionProfilerEnabled(boolean sessionProfilerEnabled) {
        if (sessionProfilerEnabled) {
            Instabug.setSessionProfilerState(Feature.State.ENABLED);
        } else {
            Instabug.setSessionProfilerState(Feature.State.DISABLED);
        }
    }

    /**
     * Enable/disable SDK logs
     *
     * @param debugEnabled desired state of debug mode
     */
    public void setDebugEnabled(boolean debugEnabled) {
        Instabug.setDebugEnabled(debugEnabled);
    }

    /**
     * Set the primary color that the SDK will use to tint certain UI elements in
     * the SDK
     *
     * @param primaryColor The value of the primary color
     */
    public void setPrimaryColor(final long primaryColor) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Instabug.setPrimaryColor((int) primaryColor);
            }
        });
    }

    /**
     * Adds specific user data that you need to be added to the reports
     *
     * @param userData
     */
    public void setUserData(String userData) {
        Instabug.setUserData(userData);
    }

    /**
     * The file at filePath will be uploaded along upcoming reports with the name
     * fileNameWithExtension
     *
     * @param fileUri               the file uri
     * @param fileNameWithExtension the file name with extension
     */
    public void addFileAttachmentWithURL(String fileUri, String fileNameWithExtension) {
        File file = new File(fileUri);
        if (file.exists()) {
            Instabug.addFileAttachment(Uri.fromFile(file), fileNameWithExtension);
        }
    }

    /**
     * The file at filePath will be uploaded along upcoming reports with the name
     * fileNameWithExtension
     *
     * @param data                  the data of the file
     * @param fileNameWithExtension the file name with extension
     */
    public void addFileAttachmentWithData(byte[] data, String fileNameWithExtension) {
        Instabug.addFileAttachment(data, fileNameWithExtension);
    }

    /**
     * Clears all Uris of the attached files. The URIs which added via
     * {@link Instabug#addFileAttachment} API not the physical files.
     */
    public void clearFileAttachments() {
        Instabug.clearFileAttachment();
    }

    /**
     * Sets the welcome message mode to live, beta or disabled.
     *
     * @param welcomeMessageMode An enum to set the welcome message mode to live,
     *                           beta or disabled.
     */
    public void setWelcomeMessageMode(String welcomeMessageMode) {
        WelcomeMessage.State resolvedWelcomeMessageMode = ArgsRegistry.getDeserializedValue(welcomeMessageMode);
        Instabug.setWelcomeMessageState(resolvedWelcomeMessageMode);
    }

    /**
     * Enables and disables manual invocation and prompt options for bug and
     * feedback.
     * 
     * @param {boolean} isEnabled
     */
    public void setBugReportingEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (isEnabled) {
                    BugReporting.setState(Feature.State.ENABLED);
                } else {
                    BugReporting.setState(Feature.State.DISABLED);
                }
            }
        });
    }

    /**
     * Sets a block of code to be executed just before the SDK's UI is presented.
     * This block is executed on the UI thread. Could be used for performing any UI
     * changes before the SDK's UI is shown.
     */
    public void setOnInvokeCallback() {
        BugReporting.setOnInvokeCallback(new OnInvokeCallback() {
            @Override
            public void onInvoke() {
                channel.invokeMethod("onInvokeCallback", "a");
            }
        });
    }

    /**
     * Sets a block of code to be executed right after the SDK's UI is dismissed.
     * This block is executed on the UI thread. Could be used for performing any UI
     * changes after the SDK's UI is dismissed.
     */
    public void setOnDismissCallback() {
        BugReporting.setOnDismissCallback(new OnSdkDismissCallback() {
            @Override
            public void call(DismissType dismissType, ReportType reportType) {
                HashMap<String, String> params = new HashMap<>();
                params.put("dismissType", dismissType.toString());
                params.put("reportType", reportType.toString());
                channel.invokeMethod("onDismissCallback", params);
            }
        });
    }

    /**
     * Sets the event used to invoke Instabug SDK
     *
     * @param invocationEvents ArrayList of invocation events
     */
    public void setInvocationEvents(final ArrayList<String> invocationEvents) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                InstabugInvocationEvent[] invocationEventsArray = new InstabugInvocationEvent[invocationEvents.size()];
                for (int i = 0; i < invocationEvents.size(); i++) {
                    String key = invocationEvents.get(i);
                    invocationEventsArray[i] = ArgsRegistry.getDeserializedValue(key);
                }
                BugReporting.setInvocationEvents(invocationEventsArray);
            }
        });
    }

    /**
     * Sets whether attachments in bug reporting and in-app messaging are enabled or
     * not.
     *
     * @param screenshot A boolean to enable or disable screenshot attachments.
     * @param {boolean}  extraScreenShot A boolean to enable or disable extra
     *                   screenshot attachments.
     * @param {boolean}  galleryImage A boolean to enable or disable gallery image
     *                   attachments.
     * @param {boolean}  screenRecording A boolean to enable or disable screen
     *                   recording attachments.
     */
    public void setEnabledAttachmentTypes(boolean screenshot, boolean extraScreenshot, boolean galleryImage,
            boolean screenRecording) {
        BugReporting.setAttachmentTypesEnabled(screenshot, extraScreenshot, galleryImage, screenRecording);
    }

    /**
     * Sets what type of reports, bug or feedback, should be invoked.
     * 
     * @param {array} reportTypes - Array of reportTypes
     */
    public void setReportTypes(final ArrayList<String> reportTypes) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                int[] reportTypesArray = new int[reportTypes.size()];
                for (int i = 0; i < reportTypes.size(); i++) {
                    String key = reportTypes.get(i);
                    reportTypesArray[i] = ArgsRegistry.getDeserializedValue(key);
                }
                BugReporting.setReportTypes(reportTypesArray);
            }
        });
    }

    /**
     * Sets whether the extended bug report mode should be disabled, enabled with
     * required fields, or enabled with optional fields.
     *
     * @param extendedBugReportMode
     */
    public void setExtendedBugReportMode(String extendedBugReportMode) {
        ExtendedBugReport.State extendedBugReport = ArgsRegistry.getDeserializedValue(extendedBugReportMode);
        BugReporting.setExtendedBugReportState(extendedBugReport);
    }

    /**
     * Sets the invocation options
     *
     * @param invocationOptions the array of invocation options
     */
    public void setInvocationOptions(List<String> invocationOptions) {
        int[] options = new int[invocationOptions.size()];
        for (int i = 0; i < invocationOptions.size(); i++) {
            options[i] = ArgsRegistry.getDeserializedValue(invocationOptions.get(i));
        }
        BugReporting.setOptions(options);
    }

    /**
     * Invoke bug reporting with report type and options.
     * 
     * @param {reportType}        type
     * @param {invocationOptions} options
     */
    public void showBugReportingWithReportTypeAndOptions(final String reportType,
            final List<String> invocationOptions) {
        int[] options = new int[invocationOptions.size()];
        for (int i = 0; i < invocationOptions.size(); i++) {
            options[i] = ArgsRegistry.getDeserializedValue(invocationOptions.get(i));
        }
        int reportTypeInt = ArgsRegistry.getDeserializedValue(reportType);
        BugReporting.show(reportTypeInt, options);
    }

    /**
     * Show any valid survey if exist
     *
     * @param {isEnabled} boolean
     */
    public void setSurveysEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (isEnabled) {
                    Surveys.setState(Feature.State.ENABLED);
                } else {
                    Surveys.setState(Feature.State.DISABLED);
                }
            }
        });
    }

    /**
     * Set Surveys auto-showing state, default state auto-showing enabled
     *
     * @param isEnabled whether Surveys should be auto-showing or not
     */
    public void setAutoShowingSurveysEnabled(boolean isEnabled) {
        Surveys.setAutoShowingEnabled(isEnabled);
    }

    /**
     * Sets the runnable that gets executed just before showing any valid
     * survey<br/>
     * WARNING: This runs on your application's main UI thread. Please do not
     * include any blocking operations to avoid ANRs.
     */
    public void setOnShowSurveyCallback() {
        Surveys.setOnShowCallback(new OnShowCallback() {
            @Override
            public void onShow() {
                channel.invokeMethod("onShowSurveyCallback", null);
            }
        });
    }

    /**
     * Sets the runnable that gets executed just after showing any valid survey<br/>
     * WARNING: This runs on your application's main UI thread. Please do not
     * include any blocking operations to avoid ANRs.
     *
     */
    public void setOnDismissSurveyCallback() {

        Surveys.setOnDismissCallback(new OnDismissCallback() {
            @Override
            public void onDismiss() {
                channel.invokeMethod("onDismissSurveyCallback", null);
            }
        });
    }

    /**
     * Returns an array containing the available surveys.*
     */
    public void getAvailableSurveys() {
        List<Survey> availableSurveys = Surveys.getAvailableSurveys();
        ArrayList<String> result = new ArrayList<>();
        for (Survey obj : availableSurveys) {
            result.add(obj.getTitle());
        }
        channel.invokeMethod("availableSurveysCallback", result);
    }

    /**
     * Set Surveys welcome screen enabled, default value is false
     *
     * @param shouldShow shouldShow whether should a welcome screen be shown before
     *                   taking surveys or not
     */
    public void setShouldShowSurveysWelcomeScreen(boolean shouldShow) {
        Surveys.setShouldShowWelcomeScreen(shouldShow);
    }

    /**
     * Show any valid survey if exist
     *
     * @return true if a valid survey was shown otherwise false
     */
    public void showSurveysIfAvailable() {
        Surveys.showSurveyIfAvailable();
    }

    /**
     * Shows survey with a specific token. Does nothing if there are no available
     * surveys with that specific token. Answered and cancelled surveys won't show
     * up again.
     *
     * @param surveyToken A String with a survey token.
     */
    public void showSurveyWithToken(String surveyToken) {
        Surveys.showSurvey(surveyToken);
    }

    /**
     * Returns true if the survey with a specific token was answered before. Will
     * return false if the token does not exist or if the survey was not answered
     * before.
     *
     * @param surveyToken the attribute key as string
     * @return the desired value of whether the user has responded to the survey or
     *         not.
     */
    public void hasRespondedToSurveyWithToken(String surveyToken) {
        boolean hasResponded;
        hasResponded = Surveys.hasRespondToSurvey(surveyToken);
        channel.invokeMethod("hasRespondedToSurveyCallback", hasResponded);
    }

    /**
     * Shows the UI for feature requests list
     */
    public void showFeatureRequests() {
        FeatureRequests.show();
    }

    /**
     * Sets whether email field is required or not when submitting
     * new-feature-request/new-comment-on-feature
     *
     * @param isEmailRequired set true to make email field required
     * @param actionTypes     Bitwise-or of actions
     */
    public void setEmailFieldRequiredForFeatureRequests(final Boolean isEmailRequired, final List<String> actionTypes) {
        int[] actions = new int[actionTypes.size()];
        for (int i = 0; i < actionTypes.size(); i++) {
            actions[i] = ArgsRegistry.getDeserializedValue(actionTypes.get(i));
        }
        FeatureRequests.setEmailFieldRequired(isEmailRequired, actions);
    }

    /**
     * Enables and disables everything related to receiving replies.
     * 
     * @param {boolean} isEnabled
     */
    public void setRepliesEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (isEnabled) {
                    Replies.setState(Feature.State.ENABLED);
                } else {
                    Replies.setState(Feature.State.DISABLED);
                }
            }
        });
    }

    /**
     * Manual invocation for replies.
     */
    public void showReplies() {
        Replies.show();
    }

    /**
     * Tells whether the user has chats already or not.
     */
    public void hasChats() {
        boolean hasChats = Replies.hasChats();
        channel.invokeMethod("hasChatsCallback", hasChats);
    }

    /**
     * Sets a block of code that gets executed when a new message is received.
     */
    public void setOnNewReplyReceivedCallback() {
        Runnable onNewMessageRunnable = new Runnable() {
            @Override
            public void run() {
                channel.invokeMethod("onNewReplyReceivedCallback", null);
            }
        };
        Replies.setOnNewReplyReceivedCallback(onNewMessageRunnable);
    }

    /**
     * Get current unread count of messages for this user
     *
     * @return number of messages that are unread for this user
     */
    public void getUnreadRepliesCount() {
        int unreadMessages = Replies.getUnreadRepliesCount();
        channel.invokeMethod("unreadRepliesCountCallback", unreadMessages);
    }

    /**
     * Enabled/disable chat notification
     *
     * @param isChatNotificationEnable whether chat notification is reburied or not
     */
    public void setChatNotificationEnabled(boolean isChatNotificationEnable) {
        Replies.setInAppNotificationEnabled(isChatNotificationEnable);
    }

    /**
     * Set whether new in app notification received will play a small sound
     * notification or not (Default is {@code false})
     *
     * @param shouldPlaySound desired state of conversation sounds
     * @since 4.1.0
     */
    public void setEnableInAppNotificationSound(boolean shouldPlaySound) {
        Replies.setInAppNotificationSound(shouldPlaySound);
    }

    /**
     * Extracts HTTP connection properties. Request method, Headers, Date, Url and
     * Response code
     *
     * @param jsonObject the JSON object containing all HTTP connection properties
     */
    public void networkLog(HashMap<String, Object> jsonObject) throws JSONException {

        int responseCode = 0;

        NetworkLog networkLog = new NetworkLog();
        String date = System.currentTimeMillis() + "";
        networkLog.setDate(date);
        networkLog.setUrl((String) jsonObject.get("url"));
        networkLog.setRequest((String) jsonObject.get("requestBody"));
        networkLog.setResponse((String) jsonObject.get("responseBody"));
        networkLog.setMethod((String) jsonObject.get("method"));
        networkLog.setResponseCode((Integer) jsonObject.get("responseCode"));
        networkLog.setRequestHeaders(
                (new JSONObject((HashMap<String, String>) jsonObject.get("requestHeaders"))).toString(4));
        networkLog.setResponseHeaders(
                (new JSONObject((HashMap<String, String>) jsonObject.get("responseHeaders"))).toString(4));
        networkLog.setTotalDuration(((Number) jsonObject.get("duration")).longValue() / 1000);
        networkLog.insert();
    }

    /**
     * Enables and disables automatic crash reporting.
     * 
     * @param {boolean} isEnabled
     */
    public void setCrashReportingEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (isEnabled) {
                    CrashReporting.setState(Feature.State.ENABLED);
                } else {
                    CrashReporting.setState(Feature.State.DISABLED);
                }
            }
        });
    }

    public void sendJSCrashByReflection(final String map, final boolean isHandled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject exceptionObject = new JSONObject(map);
                    Method method = getMethod(Class.forName("com.instabug.crash.CrashReporting"), "reportException",
                            JSONObject.class, boolean.class);
                    if (method != null) {
                        method.invoke(null, exceptionObject, isHandled);
                        Log.e("IBG-Flutter", exceptionObject.toString());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Enables and disables everything related to APM feature.
     * 
     * @param {boolean} isEnabled
     */
    public void setAPMEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    APM.setEnabled(isEnabled);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Sets the printed logs priority. Filter to one of the following levels.
     *
     * @param {String} logLevel.
     */
    public void setAPMLogLevel(final String logLevel) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ArgsRegistry.getDeserializedValue(logLevel) == null) {
                        return;
                    }
                    APM.setLogLevel((int) ArgsRegistry.getRawValue(logLevel));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
  
    /**
     * Enables or disables cold app launch tracking.
     * @param isEnabled boolean indicating enabled or disabled.
     */
    public void setColdAppLaunchEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    APM.setAppLaunchEnabled(isEnabled);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
    /**
     * Starts an execution trace
     * @param name string name of the trace.
     */
    public String startExecutionTrace(final String name, final String id) {
        try {
            String result = null;
            ExecutionTrace trace = APM.startExecutionTrace(name);
            if (trace != null) {
                result = id;
                traces.put(id, trace);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
  
    /**
     * Sets an execution trace attribute
     * @param id string id of the trace.
     * @param key string key of the attribute.
     * @param value string value of the attribute.
     */
    public void setExecutionTraceAttribute(final String id, final String key, final String value) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    traces.get(id).setAttribute(key, value);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
  
    /**
     * Ends an execution trace
     * @param id string id of the trace.
     */
    public void endExecutionTrace(final String id) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    traces.get(id).end();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
  
    /**
     * Enables or disables auto UI tracing
     * @param isEnabled boolean indicating enabled or disabled.
     */
    public void setAutoUITraceEnabled(final boolean isEnabled) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    APM.setAutoUITraceEnabled(isEnabled);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Starts a UI trace
     * @param name string name of the UI trace.
     */
    public void startUITrace(final String name) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    APM.startUITrace(name);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Ends the current running UI trace
     */
    public void endUITrace() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    APM.endUITrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Ends app launch
     */
    public void endAppLaunch() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    APM.endAppLaunch();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void apmNetworkLogByReflection(HashMap<String, Object> jsonObject) throws JSONException {
        try {
            APMNetworkLogger apmNetworkLogger = new APMNetworkLogger();
            final String requestUrl = (String) jsonObject.get("url");
            final String requestBody = (String) jsonObject.get("requestBody");
            final String responseBody = (String) jsonObject.get("responseBody");
            final String requestMethod = (String) jsonObject.get("method");
            //--------------------------------------------
            final String requestContentType = (String) jsonObject.get("requestContentType");
            final String responseContentType = (String) jsonObject.get("responseContentType");
            //--------------------------------------------
            final long requestBodySize = ((Number) jsonObject.get("requestBodySize")).longValue();
            final long responseBodySize = ((Number) jsonObject.get("responseBodySize")).longValue();
            //--------------------------------------------
            final String errorDomain = (String) jsonObject.get("errorDomain");
            final Integer statusCode = (Integer) jsonObject.get("responseCode");
            final long requestDuration = ((Number) jsonObject.get("duration")).longValue() / 1000;
            final long requestStartTime = ((Number) jsonObject.get("startTime")).longValue() * 1000;
            final String requestHeaders = (new JSONObject((HashMap<String, String>) jsonObject.get("requestHeaders"))).toString(4);
            final String responseHeaders = (new JSONObject((HashMap<String, String>) jsonObject.get("responseHeaders"))).toString(4);
            final String errorMessage;

            if(errorDomain.equals("")) {
                errorMessage = null;
            } else {
                errorMessage = errorDomain;
            }
            //--------------------------------------------------
            String gqlQueryName = null;
            if(jsonObject.containsKey("gqlQueryName")){
                gqlQueryName = (String) jsonObject.get("gqlQueryName");
            }
            String serverErrorMessage = "";
            if(jsonObject.containsKey("serverErrorMessage")){
                serverErrorMessage = (String) jsonObject.get("serverErrorMessage");
            }  

            try {
                Method method = getMethod(Class.forName("com.instabug.apm.networking.APMNetworkLogger"), "log", long.class, long.class, String.class, String.class, long.class, String.class, String.class, String.class, String.class, String.class, long.class, int.class, String.class, String.class, String.class, String.class);
                if (method != null) {
                    method.invoke(apmNetworkLogger, requestStartTime, requestDuration, requestHeaders, requestBody, requestBodySize, requestMethod, requestUrl, requestContentType, responseHeaders, responseBody, responseBodySize, statusCode, responseContentType, errorMessage, gqlQueryName, serverErrorMessage);
                } else {
                    Log.e("IB-CP-Bridge", "apmNetworkLogByReflection was not found by reflection");
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /*
     * 
     * Reports that the screen has been
     * 
     * changed (Repro Steps) the screen sent to this method will be the 'current
     * view' on the dashboard
     *
     * @param screenName string containing the screen name
     *
     */

    public void reportScreenChange(String screenName) {
        try {
            Method method = getMethod(Class.forName("com.instabug.library.Instabug"), "reportScreenChange",
                    Bitmap.class, String.class);
            if (method != null) {
                method.invoke(null, null, screenName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sets the Repro Steps mode
     *
     * @param reproStepsMode string repro step mode
     *
     */
    public void setReproStepsMode(String reproStepsMode) {
        try {
            Instabug.setReproStepsState(ArgsRegistry.getDeserializedValue(reproStepsMode));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sets the threshold value of the shake gesture for android devices. Default
     * for android is an integer value equals 350. you could increase the shaking
     * difficulty level by increasing the `350` value and vice versa
     * 
     * @param androidThreshold Threshold for android devices.
     */
    public void setShakingThresholdForAndroid(int androidThreshold) {
        BugReporting.setShakingThreshold(androidThreshold);
    }

    /**
     * Enables all Instabug functionality
     */
    public void enable() {
        Instabug.enable();
    }

    /**
     * Disables all Instabug functionality
     */
    public void disable() {
        Instabug.disable();
    }


}