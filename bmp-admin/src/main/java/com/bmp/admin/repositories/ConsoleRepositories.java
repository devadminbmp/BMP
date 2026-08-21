package com.bmp.admin.repositories;

/**
 * Marker/doc file — the console's repositories live in sibling files in this package, one per
 * interface, because Spring Data's scanner is happiest that way and nested interfaces inside a
 * container class are a scanning risk nobody should have to debug.
 *
 * <p>See: {@link PlatformSettingRepository}, {@link ContentReportRepository},
 * {@link RefundRequestRepository}, {@link SalonReviewRepository}, {@link DataRequestRepository}.
 */
final class ConsoleRepositories {
    private ConsoleRepositories() {}
}
