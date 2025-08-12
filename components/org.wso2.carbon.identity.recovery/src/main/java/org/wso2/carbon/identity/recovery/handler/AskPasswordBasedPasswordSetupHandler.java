/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.recovery.handler;

import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.core.model.IdentityErrorMsgContext;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.governance.IdentityMgtConstants;
import org.wso2.carbon.identity.governance.service.notification.NotificationChannels;
import org.wso2.carbon.identity.recovery.IdentityRecoveryConstants;
import org.wso2.carbon.identity.recovery.IdentityRecoveryException;
import org.wso2.carbon.identity.recovery.IdentityRecoveryServerException;
import org.wso2.carbon.identity.recovery.RecoveryScenarios;
import org.wso2.carbon.identity.recovery.RecoverySteps;
import org.wso2.carbon.identity.recovery.model.UserRecoveryData;
import org.wso2.carbon.identity.recovery.store.JDBCRecoveryDataStore;
import org.wso2.carbon.identity.recovery.store.UserRecoveryDataStore;
import org.wso2.carbon.identity.recovery.util.Utils;
import org.wso2.carbon.user.api.Claim;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;

import java.util.Map;

import static org.wso2.carbon.identity.mgt.constants.IdentityMgtConstants.Notification.ASK_PASSWORD;

/**
 * This class handles the ask password-based password setup flow.
 * It extends the AdminForcedPasswordResetHandler to handle the password setup process.
 */
public class AskPasswordBasedPasswordSetupHandler extends AdminForcedPasswordResetHandler {

    private static final Boolean isRandomValueForCredentialsDisabled = isRandomValueForCredentialsDisabled();

    public String getName() {

        return "askPasswordBasedPasswordSetup";
    }

    public String getFriendlyName() {

        return "Ask Password Based Password Setup Handler";
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        Map<String, Object> eventProperties = event.getEventProperties();
        String eventName = event.getEventName();

        Map<String, String> claims = (Map<String, String>) eventProperties.get(IdentityEventConstants.EventProperty
                .USER_CLAIMS);

        if (IdentityEventConstants.Event.PRE_ADD_USER.equals(eventName)) {
            if (claims == null || claims.isEmpty()) {
                return;
            }
            if (claims.containsKey(IdentityRecoveryConstants.ASK_PASSWORD_CLAIM) &&
                    Boolean.parseBoolean(claims.get(IdentityRecoveryConstants.ASK_PASSWORD_CLAIM))) {
                Claim claim = new Claim();
                claim.setClaimUri(IdentityRecoveryConstants.ASK_PASSWORD_CLAIM);
                claim.setValue(claims.get(IdentityRecoveryConstants.ASK_PASSWORD_CLAIM));
                Utils.setAskPasswordTemporaryClaim(claim);
                claims.remove(IdentityRecoveryConstants.ASK_PASSWORD_CLAIM);
                Object credentials = eventProperties.get(IdentityEventConstants.EventProperty.CREDENTIAL);
                if (!isRandomValueForCredentialsDisabled) {
                    setRandomValueForCredentials(credentials);
                }
                Utils.publishRecoveryEvent(eventProperties, IdentityEventConstants.Event.PRE_ADD_USER_WITH_ASK_PASSWORD,
                        null);
            }
        } else if (IdentityEventConstants.Event.POST_ADD_USER.equals(eventName)) {
            Claim claim = Utils.getAskPasswordTemporaryClaim();
            if (claim == null) {
                return;
            }
            if (IdentityRecoveryConstants.ASK_PASSWORD_CLAIM.equals(claim.getClaimUri())) {
                String confirmationCode;
                UserStoreManager userStoreManager = (UserStoreManager) eventProperties.get(
                        IdentityEventConstants.EventProperty.USER_STORE_MANAGER);
                User user = getUser(eventProperties, userStoreManager);
                String tenantDomain = user.getTenantDomain();
                boolean isAccountClaimExist = Utils.isAccountStateClaimExisting(tenantDomain);
                boolean isAccountLockOnCreation = Boolean.parseBoolean(Utils.getConnectorConfig
                        (IdentityRecoveryConstants.ConnectorConfig.EMAIL_ACCOUNT_LOCK_ON_CREATION, tenantDomain));
                boolean isNotificationInternallyManage = Boolean.parseBoolean(Utils.getConnectorConfig
                        (IdentityRecoveryConstants.ConnectorConfig.EMAIL_VERIFICATION_NOTIFICATION_INTERNALLY_MANAGE,
                                tenantDomain));

                boolean isAskPasswordEmailOTPEnabled = Boolean.parseBoolean(Utils.getConnectorConfig(
                        IdentityRecoveryConstants.ConnectorConfig.ASK_PASSWORD_SEND_EMAIL_OTP, tenantDomain));
                boolean isAskPasswordSMSOTPEnabled = Boolean.parseBoolean(Utils.getConnectorConfig(
                        IdentityRecoveryConstants.ConnectorConfig.ASK_PASSWORD_SEND_SMS_OTP, tenantDomain));

                String channel = NotificationChannels.EMAIL_CHANNEL.getChannelType();
                RecoveryScenarios recoveryScenario = RecoveryScenarios.ASK_PASSWORD;
                RecoverySteps recoveryStep = RecoverySteps.UPDATE_PASSWORD;
                String notificationType = IdentityRecoveryConstants.NOTIFICATION_TYPE_ASK_PASSWORD;

                if (isAskPasswordEmailOTPEnabled) {
                    recoveryScenario = RecoveryScenarios.ASK_PASSWORD_VIA_EMAIL_OTP;
                    recoveryStep = RecoverySteps.SET_PASSWORD;
                    notificationType = IdentityRecoveryConstants.NOTIFICATION_TYPE_ASK_PASSWORD_EMAIL_OTP;
                } else if (isAskPasswordSMSOTPEnabled) {
                    channel = NotificationChannels.SMS_CHANNEL.getChannelType();
                    recoveryScenario = RecoveryScenarios.ASK_PASSWORD_VIA_SMS_OTP;
                    recoveryStep = RecoverySteps.SET_PASSWORD;
                    notificationType = IdentityRecoveryConstants.NOTIFICATION_TYPE_ASK_PASSWORD_SMS_OTP;
                }

                try {
                    confirmationCode = Utils.generateSecretKey(channel, recoveryScenario.name(), tenantDomain,
                            "EmailVerification");
                } catch (IdentityRecoveryServerException e) {
                    throw new IdentityEventException("Error while fetching the OTP pattern ", e);
                }
                if (isAccountClaimExist) {
                    setUserClaim(IdentityRecoveryConstants.ACCOUNT_STATE_CLAIM_URI,
                            IdentityRecoveryConstants.PENDING_ASK_PASSWORD, userStoreManager, user);
                }
                if (isNotificationInternallyManage) {
                    if (isAskPasswordSMSOTPEnabled) {
                        try {
                            storeRecoveryData(user, recoveryScenario, recoveryStep, confirmationCode);
                            String mobileNumber = userStoreManager.getUserClaimValue(user.getUserName(),
                                    IdentityRecoveryConstants.MOBILE_NUMBER_CLAIM, null);
                            triggerSmsNotification(user, notificationType, confirmationCode, mobileNumber);
                        } catch (IdentityRecoveryServerException e) {
                            throw new IdentityEventException("Error while triggering the SMS OTP.", e);
                        } catch (UserStoreException e) {
                            throw new IdentityEventException("Error while getting the mobile number for user: " +
                                    user.getLoggableMaskedUserId(), e);
                        }
                    } else {
                        initNotification(user, recoveryScenario, recoveryStep, notificationType, confirmationCode);
                    }
                } else {
                    setRecoveryData(user, RecoveryScenarios.ASK_PASSWORD, recoveryStep, confirmationCode);
                    setAskPasswordConfirmationCodeToThreadLocal(confirmationCode);
                }

                // Need to lock user account.
                if (isAccountLockOnCreation) {
                    lockAccount(user, userStoreManager);
                    setUserClaim(IdentityRecoveryConstants.ACCOUNT_LOCKED_REASON_CLAIM,
                            IdentityMgtConstants.LockedReason.PENDING_ASK_PASSWORD.toString(),
                            userStoreManager, user);
                }
                Utils.publishRecoveryEvent(eventProperties,
                        IdentityEventConstants.Event.POST_ADD_USER_WITH_ASK_PASSWORD, confirmationCode);
            }
            Utils.clearAskPasswordTemporaryClaim();
        } else if (IdentityEventConstants.Event.PRE_AUTHENTICATION.equals(eventName)) {
            UserStoreManager userStoreManager = (UserStoreManager) eventProperties
                    .get(IdentityEventConstants.EventProperty.USER_STORE_MANAGER);
            User user = getUser(eventProperties, userStoreManager);

            UserRecoveryData userRecoveryData = getRecoveryData(user);
            if (userRecoveryData != null) {
                Enum recoveryScenario = userRecoveryData.getRecoveryScenario();
                if (RecoveryScenarios.ASK_PASSWORD_VIA_EMAIL_OTP.equals(recoveryScenario) ||
                        RecoveryScenarios.ASK_PASSWORD_VIA_SMS_OTP.equals(recoveryScenario)) {
                    String credential = (String) eventProperties.get(IdentityEventConstants.EventProperty.CREDENTIAL);
                    String errorCode;
                    String errorMsg = "User : " + user.toString();
                    if (userRecoveryData.getSecret().equals(credential)) {
                        errorCode = IdentityCoreConstants.ASK_PASSWORD_SET_PASSWORD_VIA_OTP_ERROR_CODE;
                        errorMsg = errorMsg + " has given correct OTP";
                    } else {
                        errorCode = IdentityCoreConstants.ASK_PASSWORD_SET_PASSWORD_VIA_OTP_MISMATCHED_ERROR_CODE;
                        errorMsg = errorMsg + " has given in-correct OTP";
                    }
                    IdentityErrorMsgContext customErrorMessageContext = new IdentityErrorMsgContext(errorCode);
                    IdentityUtil.setIdentityErrorMsg(customErrorMessageContext);
                    throw new IdentityEventException(errorMsg);
                }
            }
        }
    }

    private static boolean isRandomValueForCredentialsDisabled() {

        return Boolean.parseBoolean(IdentityUtil.getProperty(
                IdentityRecoveryConstants.ConnectorConfig.ASK_PASSWORD_DISABLE_RANDOM_VALUE_FOR_CREDENTIALS));
    }

    /**
     * This method sets a random value for the credentials, if the ask password flow is enabled.
     *
     * @param credentials Credentials object
     */
    private void setRandomValueForCredentials(Object credentials) {

        char[] temporaryPassword = Utils.generateRandomPassword(12);
        ((StringBuffer) credentials).replace(0, temporaryPassword.length, new String(temporaryPassword));
    }

    /**
     * To set the confirmation code to ask password thread local.
     *
     * @param confirmationCode Ask password confirmation code.
     */
    private void setAskPasswordConfirmationCodeToThreadLocal(String confirmationCode) {

        Object initialValue = IdentityUtil.threadLocalProperties.get()
                .get(IdentityRecoveryConstants.AP_CONFIRMATION_CODE_THREAD_LOCAL_PROPERTY);
        if (initialValue != null &&
                IdentityRecoveryConstants.AP_CONFIRMATION_CODE_THREAD_LOCAL_INITIAL_VALUE
                        .equals(initialValue.toString())) {
            IdentityUtil.threadLocalProperties.get()
                    .put(IdentityRecoveryConstants.AP_CONFIRMATION_CODE_THREAD_LOCAL_PROPERTY,
                            confirmationCode);
        }
    }

    private void storeRecoveryData(User user, Enum recoveryScenario, Enum recoveryStep, String secretKey)
            throws IdentityEventException {

        UserRecoveryDataStore userRecoveryDataStore = JDBCRecoveryDataStore.getInstance();
        try {
            userRecoveryDataStore.invalidate(user);
            UserRecoveryData recoveryDataDO = new UserRecoveryData(user, secretKey, recoveryScenario, recoveryStep);

            userRecoveryDataStore.store(recoveryDataDO);
        } catch (IdentityRecoveryException e) {
            throw new IdentityEventException("Error while storing ask password flow recovery data.", e);
        }
    }
}
