/**
 * Review MODULE — verified reviews (booking_id required), dual rating, moderation.
 *
 * <p>Public surface: com.bmp.review.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: review_schema — review, review_edit_history, review_prompt,
 * salon_rating_snapshot, stylist_rating_snapshot, salon_response. (The community
 * feed bridge — community_posts, post_likes, post_comments, stylist_follows,
 * post_engagement_score — lives in MongoDB, a separate datastore, not this schema.)
 *
 * <p>UPDATED: this comment previously listed a simpler 3-table version (review,
 * review_photo, salon_response). CONTEXT.md's later, more detailed Session-3
 * design specifies the 6 Postgres tables above instead — corrected to match.
 * See V007__review_schema.sql.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Review",
    allowedDependencies = { "common" }
)
package com.bmp.review;
