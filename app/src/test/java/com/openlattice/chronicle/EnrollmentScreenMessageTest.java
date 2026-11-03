package com.openlattice.chronicle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EnrollmentScreenMessageTest {
    @Test
    public void aNewMessagePreservesEveryScreenAndItsBusyState() {
        EnrollmentScreen[] screens = {
            new EnrollmentScreen.Failure(R.string.device_enroll_failure),
            new EnrollmentScreen.Editing(null),
            new EnrollmentScreen.Reviewing(true, null),
            new EnrollmentScreen.Reviewing(false, null),
            new EnrollmentScreen.Submitting(null),
            new EnrollmentScreen.Recovery(RecoveryKind.PENDING, R.string.enrollment_pending_retry),
            new EnrollmentScreen.Result(R.string.device_enroll_success),
        };
        for (EnrollmentScreen screen : screens) {
            EnrollmentScreen updated = EnrollmentFormKt.withMessage(screen, R.string.enrollment_finish_current_first);
            assertEquals(screen.getClass().getSimpleName(),
                Integer.valueOf(R.string.enrollment_finish_current_first), updated.getMessage());
            assertEquals(screen.getClass(), updated.getClass());
            assertEquals(EnrollmentFormKt.isBusy(screen), EnrollmentFormKt.isBusy(updated));
        }
    }
}
