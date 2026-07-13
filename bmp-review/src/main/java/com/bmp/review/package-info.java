/**
 * Review MODULE — verified reviews (booking_id required), dual rating, moderation.
 *
 * <p>Public surface: com.bmp.review.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: review_schema — review, review_photo, salon_response.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Review",
    allowedDependencies = { "common" }
)
package com.bmp.review;
