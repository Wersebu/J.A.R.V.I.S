package com.jarvis.tools.dataset;

/**
 * One canonical store record inside a {@link StoreAuditDataset}. Immutable - every mutation
 * (verification, geolocation) produces a new instance via the {@code with*} methods, and only
 * {@link StoreAuditDatasetService} ever replaces a record in the dataset, always by id, never by
 * appending or removing.
 *
 * <p>Exactly one {@code StoreRecord} exists per visible store row in the source material - this
 * invariant is enforced at creation time by {@link StoreAuditDatasetService}, not by this type.</p>
 *
 * @param id canonical record id (e.g. {@code "store-001"})
 * @param network store network/chain name (e.g. "Biedronka")
 * @param city city name
 * @param street street name
 * @param buildingNumber building number
 * @param postalCode postal code
 * @param fullAddress full address as one string
 * @param sourceAttachmentId id of the current-message attachment this record was extracted from -
 *         the record's provenance; blank only when the whole dataset was extracted from an
 *         explicit typed list instead of an attachment
 * @param sourceRow row/position of this record within its source attachment
 * @param verificationStatus second-pass verification state
 * @param geolocationStatus geolocation state
 * @param latitude resolved latitude, once geolocated
 * @param longitude resolved longitude, once geolocated
 */
public record StoreRecord(
        String id,
        String network,
        String city,
        String street,
        String buildingNumber,
        String postalCode,
        String fullAddress,
        String sourceAttachmentId,
        int sourceRow,
        VerificationStatus verificationStatus,
        GeolocationStatus geolocationStatus,
        Double latitude,
        Double longitude
) {

    /**
     * Normalizes null fields.
     */
    public StoreRecord {
        id = id == null ? "" : id;
        network = network == null ? "" : network;
        city = city == null ? "" : city;
        street = street == null ? "" : street;
        buildingNumber = buildingNumber == null ? "" : buildingNumber;
        postalCode = postalCode == null ? "" : postalCode;
        fullAddress = fullAddress == null ? "" : fullAddress;
        sourceAttachmentId = sourceAttachmentId == null ? "" : sourceAttachmentId;
        verificationStatus = verificationStatus == null ? VerificationStatus.UNVERIFIED : verificationStatus;
        geolocationStatus = geolocationStatus == null ? GeolocationStatus.PENDING : geolocationStatus;
    }

    /**
     * Returns a copy with an updated verification status and optionally corrected fields.
     *
     * @param status new verification status
     * @param correctedFullAddress corrected address, or null to keep the current value
     * @param correctedPostalCode corrected postal code, or null to keep the current value
     * @return updated record with the same id and provenance
     */
    public StoreRecord withVerification(VerificationStatus status, String correctedFullAddress, String correctedPostalCode) {
        return new StoreRecord(id, network, city, street, buildingNumber,
                correctedPostalCode == null || correctedPostalCode.isBlank() ? postalCode : correctedPostalCode,
                correctedFullAddress == null || correctedFullAddress.isBlank() ? fullAddress : correctedFullAddress,
                sourceAttachmentId, sourceRow, status, geolocationStatus, latitude, longitude);
    }

    /**
     * Returns a copy with an updated geolocation result.
     *
     * @param status new geolocation status
     * @param newLatitude resolved latitude, or null when unresolved
     * @param newLongitude resolved longitude, or null when unresolved
     * @return updated record with the same id and provenance
     */
    public StoreRecord withGeolocation(GeolocationStatus status, Double newLatitude, Double newLongitude) {
        return new StoreRecord(id, network, city, street, buildingNumber, postalCode, fullAddress,
                sourceAttachmentId, sourceRow, verificationStatus, status, newLatitude, newLongitude);
    }
}
