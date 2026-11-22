package com.ramsbaby.preply.component;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CalendarSnapshotTest {
    final ZoneId kst = ZoneId.of("Asia/Seoul");
    final LocalDate day = LocalDate.parse("2026-11-18");
    final CalendarSnapshot source = new CalendarSnapshot("", null, new BigDecimal("0.82"), "2026-11-15");
    CalendarSnapshot.Lesson lesson(String id, int hour, String status, String gross, String earned) {
        Instant start = day.atTime(hour,0).atZone(kst).toInstant();
        return new CalendarSnapshot.Lesson(id,start,start.plusSeconds(3600),"student",status,
                new BigDecimal(gross),BigDecimal.ONE,new BigDecimal(earned),false);
    }
    @Test void countsEachSessionAndPaidCancellationOnceByLessonDate() {
        var rows = List.of(lesson("a",9,"AUTOCOMPLETED","40","32.80"),lesson("b",10,"WASTED","45","36.90"),
                lesson("c",20,"COMPLETED","30","24.60"), lesson("d",21,"AUTOCOMPLETED","22","18.04"),
                lesson("e",22,"BOOKED","22","0"),lesson("f",14,"CANCELLED","60","0"));
        var last = day.plusDays(1).atStartOfDay(kst).toInstant();
        var all = source.calculate(new CalendarSnapshot.Snapshot(day,last,rows),kst);
        assertThat(all.scheduled()).hasSize(4);
        assertThat(all.cancelled()).hasSize(1);
        assertThat(all.totalsByCurrency().get("USD")).isEqualByComparingTo("130.38");
        var atTen = source.calculate(new CalendarSnapshot.Snapshot(day,day.atTime(22,0).atZone(kst).toInstant(),rows),kst);
        assertThat(atTen.upcoming()).hasSize(1);
        assertThat(atTen.totalsByCurrency().get("USD")).isEqualByComparingTo("112.34");
    }
    @Test void duplicatesAndUnknownStatusesCannotProduceMail() {
        var l = lesson("a",9,"BOOKED","30","0");
        assertThatThrownBy(()->source.calculate(new CalendarSnapshot.Snapshot(day,Instant.now(),List.of(l,l)),kst)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->source.calculate(new CalendarSnapshot.Snapshot(day,Instant.now(),List.of(lesson("b",9,"UNKNOWN","30","0"))),kst)).isInstanceOf(IllegalStateException.class);
    }
}
