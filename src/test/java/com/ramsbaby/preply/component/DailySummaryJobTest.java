package com.ramsbaby.preply.component;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.javamail.JavaMailSender;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.LessonEvent;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.RateEntry;
import com.ramsbaby.preply.port.ExchangeRatePort;
import com.ramsbaby.preply.port.LessonEventsPort;
import com.ramsbaby.preply.port.MailCachePort;
import com.ramsbaby.preply.port.RateLoaderPort;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class DailySummaryJobTest {

        @Mock
        private RateLoaderPort rateLoader;
        @Mock
        private MailCachePort supabase;
        @Mock
        private LessonEventsPort gcal;
        @Mock
        private JavaMailSender mailSender;
        @Mock
        private ExchangeRatePort fx;

        private DailySummaryJob job;
        private AppProps props;

        @BeforeEach
        void setup() {
                MockitoAnnotations.openMocks(this);
                props = new AppProps(
                                new AppProps.Mail("user", "pass",
                                                new AppProps.Mail.Imap("imap.naver.com", 993),
                                                new AppProps.Mail.Smtp("smtp.naver.com", 465, "from@naver.com",
                                                                List.of("to@naver.com"))),
                                new AppProps.Gcal("", "", "Asia/Seoul", " - Preply lesson", 90),
                                new AppProps.Supabase("url", "key", "preply_mail", true),
                                new AppProps.Run(""),
                                false);
                job = new DailySummaryJob(props, rateLoader, supabase, gcal, mailSender, fx);
                when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
                doNothing().when(mailSender).send(any(MimeMessage.class));
                when(gcal.loadTodayPreplyEvents()).thenReturn(
                                List.of(new LessonEvent("alice", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))));
                when(fx.krwPer(any())).thenReturn(BigDecimal.ONE);
        }

        @Test
        void supabase_enabled_uses_cache_only() {
                when(supabase.enabled()).thenReturn(true);
                when(supabase.findLatestBookingRates()).thenReturn(
                                Map.of("alice", new Money(new BigDecimal("10"), "USD")));
                when(supabase.findTodayCompensations(any())).thenReturn(List.of());

                assertThatCode(job::run).doesNotThrowAnyException();

                verify(supabase).findLatestBookingRates();
                verify(supabase).findTodayCompensations(any());
        }

        @Test
        void supabase_disabled_falls_back_to_imap_loaders() {
                when(supabase.enabled()).thenReturn(false);
                when(rateLoader.loadRates()).thenReturn(
                                Map.of("alice", new Money(new BigDecimal("11"), "USD")));
                when(rateLoader.loadTodayCancellationCompensations()).thenReturn(
                                List.of(new RateEntry("bob", new Money(new BigDecimal("5"), "USD"),
                                                ZonedDateTime.now())));

                assertThatCode(job::run).doesNotThrowAnyException();

                verify(rateLoader).loadRates();
                verify(rateLoader).loadTodayCancellationCompensations();
                verify(supabase, never()).findLatestBookingRates();
        }
}
