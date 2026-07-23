/**
 * Review MODULE — verified reviews (booking_id required), dual rating, moderation.
 *
 * <p>Public surface: com.bmp.review.api. Session 5: this module is now its own independently-deployable service (see CONTEXT.md) — entities/repositories/services/controllers/dto/advices/config/exceptions are flat packages under com.bmp.review, no longer nested under an internal/ package (that was the Spring Modulith convention, retired when the microservices split happened).
 * <p>Owns tables in: review_schema — review, review_edit_history, review_prompt,
 * salon_rating_snapshot, stylist_rating_snapshot, salon_response. (The community
 * feed bridge — community_posts, post_likes, post_comments, stylist_follows,
 * post_engagement_score — lives in MongoDB, a separate datastore, not this schema.)
 *
 * <p>UPDATED: this comment previously listed a simpler 3-table version (review,
 * review_photo, salon_response). CONTEXT.md's later, more detailed Session-3
 * design specifies the 6 Postgres tables above instead — corrected to match.
 * See V007__review_schema.sql.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.review;
