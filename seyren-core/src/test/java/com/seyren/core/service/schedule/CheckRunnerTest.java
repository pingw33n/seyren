/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seyren.core.service.schedule;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hamcrest.Description;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Matchers;
import org.mockito.Mockito;

import com.google.common.base.Optional;
import com.seyren.core.domain.Alert;
import com.seyren.core.domain.AlertType;
import com.seyren.core.domain.Check;
import com.seyren.core.domain.Subscription;
import com.seyren.core.domain.SubscriptionType;
import com.seyren.core.exception.NotificationFailedException;
import com.seyren.core.service.checker.TargetChecker;
import com.seyren.core.service.checker.ValueChecker;
import com.seyren.core.service.notification.NotificationService;
import com.seyren.core.store.AlertsStore;
import com.seyren.core.store.ChecksStore;

public class CheckRunnerTest {
    
    private Check mockCheck;
    private TargetChecker.Context mockContext;
    private AlertsStore mockAlertsStore;
    private ChecksStore mockChecksStore;
    private TargetChecker mockTargetChecker;
    private ValueChecker mockValueChecker;
    private NotificationService mockNotificationService;
    private Iterable<NotificationService> mockNotificationServices;
    private CheckRunner checkRunner;
    
    @Before
    public void before() {
        mockCheck = mock(Check.class);
        mockContext = new TargetChecker.Context(mockCheck, Instant.now());
        DateTimeUtils.setCurrentMillisFixed(mockContext.getNow().getMillis());
        mockAlertsStore = mock(AlertsStore.class);
        mockChecksStore = mock(ChecksStore.class);
        mockTargetChecker = mock(TargetChecker.class);
        when(mockTargetChecker.canHandle(any(Check.class))).thenReturn(true);
        mockValueChecker = mock(ValueChecker.class);
        mockNotificationService = mock(NotificationService.class);
        mockNotificationServices = Arrays.asList(mockNotificationService);
        checkRunner = new CheckRunner(
                mockCheck,
                mockAlertsStore,
                mockChecksStore,
                mockTargetChecker,
                mockValueChecker,
                mockNotificationServices);
    }
    
    @Test
    public void checkWhichIsNotEnabledDoesNothing() {
        when(mockCheck.isEnabled()).thenReturn(false);
        checkRunner.run();
    }
    
    @Test
    public void noTargetValuesUpdatesLastCheckWithUnknown() throws Exception {
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.isAllowNoData()).thenReturn(false);
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(new HashMap<String, Optional<BigDecimal>>());
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.UNKNOWN), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        checkRunner.run();
        verify(mockChecksStore).updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.UNKNOWN), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any());
    }
    
    @Test
    public void noTargetValuesButAllowNoDataUpdatesLastCheckWithOk() throws Exception {
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.isAllowNoData()).thenReturn(true);
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(new HashMap<String, Optional<BigDecimal>>());
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.OK), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        checkRunner.run();
        verify(mockChecksStore).updateStateAndLastCheckAndLastValues(eq("id"),  eq(AlertType.OK), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any());
    }
    
    @Test
    public void anExceptionWhileRunningIsHandled() throws Exception {
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockTargetChecker.check(similar(mockContext))).thenThrow(new Exception("Boom!"));
        checkRunner.run();
    }
    
    @Test
    public void emptyTargetValueDoesNothing() throws Exception {
        when(mockCheck.isEnabled()).thenReturn(true);
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.<BigDecimal>absent());
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        checkRunner.run();
    }
    
    @Test
    public void noPreviousAlertAndHappyCurrentValueDoesNothing() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(null);
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.OK);
        checkRunner.run();
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void createAlertButDoNotNotifyIfStateIsTheSame() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(any(TargetChecker.Context.class))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(new Alert().withToType(AlertType.WARN));
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.WARN);
        
        Alert alert = new Alert();
        
        when(mockAlertsStore.createAlert(eq("id"), any(Alert.class))).thenReturn(alert);
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.WARN), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        
        checkRunner.run();
        
        verify(mockAlertsStore).createAlert(eq("id"), any(Alert.class));
        verify(mockNotificationService, times(0)).sendNotification(any(Check.class), any(Subscription.class), any(List.class));
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void changeOfStateButNoSubscriptionsDoesNothing() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        when(mockCheck.getSubscriptions()).thenReturn(new ArrayList<Subscription>());
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(new Alert().withToType(AlertType.WARN));
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.ERROR);
        
        Alert alert = new Alert();
        
        when(mockAlertsStore.createAlert(eq("id"), any(Alert.class))).thenReturn(alert);
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.ERROR), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        
        checkRunner.run();
        
        verify(mockAlertsStore).createAlert(eq("id"), any(Alert.class));
        verify(mockNotificationService, times(0)).sendNotification(any(Check.class), any(Subscription.class), any(List.class));
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void changeOfStateButSubscriptionWhichShouldNotNotifyDoesNothing() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        Subscription mockSubscription = mock(Subscription.class);
        when(mockSubscription.getType()).thenReturn(SubscriptionType.EMAIL);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        when(mockCheck.getSubscriptions()).thenReturn(Arrays.asList(mockSubscription));
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(new Alert().withToType(AlertType.WARN));
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.ERROR);
        
        Alert alert = new Alert();
        
        when(mockAlertsStore.createAlert(eq("id"), any(Alert.class))).thenReturn(alert);
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.ERROR), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        when(mockSubscription.shouldNotify(any(DateTime.class), eq(AlertType.ERROR))).thenReturn(false);
        
        checkRunner.run();
        
        verify(mockAlertsStore).createAlert(eq("id"), any(Alert.class));
        verify(mockNotificationService, times(0)).sendNotification(any(Check.class), any(Subscription.class), any(List.class));
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void changeOfStateAndSubscriptionWhichShouldNotifyAndCannotHandleDoesNothing() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        Subscription mockSubscription = mock(Subscription.class);
        when(mockSubscription.getType()).thenReturn(SubscriptionType.EMAIL);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        when(mockCheck.getSubscriptions()).thenReturn(Arrays.asList(mockSubscription));
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(new Alert().withToType(AlertType.WARN));
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.ERROR);
        
        Alert alert = new Alert();
        
        when(mockAlertsStore.createAlert(eq("id"), any(Alert.class))).thenReturn(alert);
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.ERROR), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        when(mockSubscription.shouldNotify(any(DateTime.class), eq(AlertType.ERROR))).thenReturn(true);
        when(mockNotificationService.canHandle(SubscriptionType.EMAIL)).thenReturn(false);
        
        checkRunner.run();
        
        verify(mockAlertsStore).createAlert(eq("id"), any(Alert.class));
        verify(mockNotificationService, times(0)).sendNotification(eq(mockCheck), eq(mockSubscription), any(List.class));
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void changeOfStateAndSubscriptionWhichShouldNotifyAndCanHandleSendsNotification() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        Subscription mockSubscription = mock(Subscription.class);
        when(mockSubscription.getType()).thenReturn(SubscriptionType.EMAIL);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        when(mockCheck.getSubscriptions()).thenReturn(Arrays.asList(mockSubscription));
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(new Alert().withToType(AlertType.WARN));
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.ERROR);
        
        Alert alert = new Alert();
        
        when(mockAlertsStore.createAlert(eq("id"), any(Alert.class))).thenReturn(alert);
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.ERROR), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        when(mockSubscription.shouldNotify(any(DateTime.class), eq(AlertType.ERROR))).thenReturn(true);
        when(mockNotificationService.canHandle(SubscriptionType.EMAIL)).thenReturn(true);
        
        checkRunner.run();
        
        verify(mockAlertsStore).createAlert(eq("id"), any(Alert.class));
        verify(mockNotificationService).sendNotification(eq(mockCheck), eq(mockSubscription), any(List.class));
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void exceptionWhileSendingNotificationIsHandled() throws Exception {
        BigDecimal value = BigDecimal.ONE;
        BigDecimal warn = BigDecimal.valueOf(2);
        BigDecimal error = BigDecimal.valueOf(3);
        
        Subscription mockSubscription = mock(Subscription.class);
        when(mockSubscription.getType()).thenReturn(SubscriptionType.EMAIL);
        
        when(mockCheck.getId()).thenReturn("id");
        when(mockCheck.isEnabled()).thenReturn(true);
        when(mockCheck.getWarn()).thenReturn(warn);
        when(mockCheck.getError()).thenReturn(error);
        when(mockCheck.getSubscriptions()).thenReturn(Arrays.asList(mockSubscription));
        
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        targetValues.put("target", Optional.of(value));
        when(mockTargetChecker.check(similar(mockContext))).thenReturn(targetValues);
        when(mockAlertsStore.getLastAlertForTargetOfCheck("target", "id")).thenReturn(new Alert().withToType(AlertType.WARN));
        when(mockValueChecker.checkValue(value, warn, error)).thenReturn(AlertType.ERROR);
        
        Alert alert = new Alert();
        
        when(mockAlertsStore.createAlert(eq("id"), any(Alert.class))).thenReturn(alert);
        when(mockChecksStore.updateStateAndLastCheckAndLastValues(eq("id"), eq(AlertType.ERROR), any(DateTime.class),
                Matchers.<Map<String, BigDecimal>>any())).thenReturn(mockCheck);
        when(mockSubscription.shouldNotify(any(DateTime.class), eq(AlertType.ERROR))).thenReturn(true);
        when(mockNotificationService.canHandle(SubscriptionType.EMAIL)).thenReturn(true);
        Mockito.doThrow(new NotificationFailedException("Boom!")).when(mockNotificationService).sendNotification(eq(mockCheck), eq(mockSubscription), any(List.class));
        
        checkRunner.run();
        
        verify(mockAlertsStore).createAlert(eq("id"), any(Alert.class));
    }


    static class SimilarContext extends ArgumentMatcher<TargetChecker.Context> {

        private final TargetChecker.Context wanted;

        public SimilarContext(TargetChecker.Context wanted) {
            this.wanted = wanted;
        }

        @Override
        public boolean matches(Object actual) {
            if (!(actual instanceof TargetChecker.Context)) {
                return false;
            }
            TargetChecker.Context a = (TargetChecker.Context) actual;
            return a.getCheck() == wanted.getCheck() &&
                    a.getNow().equals(wanted.getNow());
        }

        public void describeTo(Description description) {
            description.appendText("similar(")
                       .appendText("" + wanted)
                       .appendText(")");
        }
    }

    TargetChecker.Context similar(TargetChecker.Context wanted) {
        return argThat(new SimilarContext(wanted));
    }
}
