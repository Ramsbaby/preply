package com.ramsbaby.preply.port;

import java.time.LocalDate;
import java.util.List;

import com.ramsbaby.preply.dto.LessonEvent;

public interface LessonEventsPort {
    List<LessonEvent> loadTodayPreplyEvents();
    List<LessonEvent> loadPreplyEvents(LocalDate date);
}

