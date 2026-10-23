package pro.teaz.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock tests can set, so syncs, cooldowns and the recurring window are computed for a known moment. */
public class TestClock extends Clock {

	public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	private volatile Instant now = Instant.now();

	/** Sets "now" to a local time in Berlin. */
	public void set(LocalDateTime berlinTime) {
		now = berlinTime.atZone(BERLIN).toInstant();
	}

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		TestClock self = this;
		return new Clock() {
			@Override
			public Instant instant() {
				return self.instant();
			}

			@Override
			public ZoneId getZone() {
				return zone;
			}

			@Override
			public Clock withZone(ZoneId other) {
				return self.withZone(other);
			}
		};
	}

	@TestConfiguration
	public static class Config {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}
}
