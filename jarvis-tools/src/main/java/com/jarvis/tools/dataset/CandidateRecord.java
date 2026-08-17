package com.jarvis.tools.dataset;

/**
 * One model-submitted candidate record for {@link StoreAuditDatasetService#createDataset}, before
 * provenance validation and id assignment.
 *
 * @param network store network/chain name
 * @param city city name
 * @param street street name
 * @param buildingNumber building number
 * @param postalCode postal code
 * @param fullAddress full address as one string
 * @param sourceAttachmentId id of the attachment this candidate was read from
 * @param sourceRow row/position within that attachment
 */
public record CandidateRecord(
        String network,
        String city,
        String street,
        String buildingNumber,
        String postalCode,
        String fullAddress,
        String sourceAttachmentId,
        int sourceRow
) {
}
