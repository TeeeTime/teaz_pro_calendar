package pro.teaz.calendar.caldav;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import pro.teaz.calendar.CalendarProperties;

/** iCloud, or made-up sample calendars with {@code calendar.sample=true}. */
@Configuration
class CalDavConfig {

	private static final Logger log = LoggerFactory.getLogger(CalDavConfig.class);

	@Bean
	CalDav calDav(CalendarProperties properties, Clock clock) {
		if (properties.sample()) {
			log.info("calendar.sample=true: using made-up sample calendars instead of iCloud.");
			return new SampleCalDav(clock, properties.timezone());
		}
		return new HttpCalDav(properties.caldavUrl());
	}
}
