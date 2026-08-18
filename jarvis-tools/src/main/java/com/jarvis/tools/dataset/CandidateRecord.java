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
 * @param sourceAttachmentId id of the attachment this candidate was read from - the real UUID.
 *         Callers that received {@link #sourceAttachmentIndex} from the model instead should leave
 *         this blank and let {@link StoreAuditDatasetService} resolve the real id deterministically;
 *         this field stays for explicit typed-list input (no attachments at all) and any caller that
 *         already knows the real id directly.
 * @param sourceRow row/position within that attachment
 * @param sourceAttachmentIndex 1-based position of the current-message attachment this candidate
 *         was read from (Image 1 = 1, Image 2 = 2, ...), as the model naturally understands it -
 *         {@code null} when not supplied. This is the PREFERRED way for a model to declare
 *         provenance: the model never has to know or copy a real attachment UUID.
 *         {@link StoreAuditDatasetService} resolves this deterministically against Core's own real,
 *         ordered current-message attachment list, overriding any {@link #sourceAttachmentId} also
 *         present - the model is never trusted to supply the real id itself when an index is given.
 */
public record CandidateRecord(
        String network,
        String city,
        String street,
        String buildingNumber,
        String postalCode,
        String fullAddress,
        String sourceAttachmentId,
        int sourceRow,
        Integer sourceAttachmentIndex
) {

    /**
     * Backward-compatible constructor for callers that only know the real attachment id directly
     * (e.g. explicit typed-list input, or a caller that already resolved the index itself).
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
    public CandidateRecord(
            String network,
            String city,
            String street,
            String buildingNumber,
            String postalCode,
            String fullAddress,
            String sourceAttachmentId,
            int sourceRow
    ) {
        this(network, city, street, buildingNumber, postalCode, fullAddress, sourceAttachmentId, sourceRow, null);
    }
}
