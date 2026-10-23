package pro.teaz.calendar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import pro.teaz.calendar.caldav.CalDavException;

/** Failed iCloud calls as problem details with a readable detail. */
@RestControllerAdvice
public class ApiErrors {

	@ExceptionHandler(CalDavException.Unavailable.class)
	public ProblemDetail unavailable(CalDavException.Unavailable e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}

	@ExceptionHandler(CalDavException.class)
	public ProblemDetail caldav(CalDavException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
	}
}
