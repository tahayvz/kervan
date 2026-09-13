package com.kervan.inventory.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockAdjustment — onay kuralları")
class StockAdjustmentTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);

    private StockAdjustment pending(String requester) {
        return StockAdjustment.pending("adj-1", "SKU-1", -500,
                AdjustmentReason.SHRINKAGE, null, requester, NOW);
    }

    @Test
    @DisplayName("İSTEYEN KENDİ İSTEĞİNİ ONAYLAYAMAZ")
    void requesterCannotApproveOwnRequest() {
        // İkinci onayın tamamı bu kural. Eşik yalnızca hangi düzeltmelerin onaya
        // düşeceğini söyler; korumayı sağlayan şey isteyenin karar verememesi.
        // Aynı kişi hem isteyip hem onaylayabilseydi süreç bir adım uzar, hiçbir
        // şey engellenmezdi.
        StockAdjustment request = pending("ayse");

        assertThatThrownBy(() -> request.approvedBy("ayse", LATER))
                .isInstanceOf(SelfApprovalException.class);
    }

    @Test
    @DisplayName("isteyen kendi isteğini REDDEDEMEZ de")
    void requesterCannotRejectOwnRequest() {
        // Reddetmek de bir karardır. Kendi isteğini reddedebilmek zararsız görünür
        // ama aynı kişinin süreci tek başına kapatmasına izin verir.
        assertThatThrownBy(() -> pending("ayse").rejectedBy("ayse", LATER))
                .isInstanceOf(SelfApprovalException.class);
    }

    @Test
    @DisplayName("başkası onaylayabilir ve karar kaydedilir")
    void anotherPersonCanApprove() {
        StockAdjustment approved = pending("ayse").approvedBy("mehmet", LATER);

        assertThat(approved.status()).isEqualTo(AdjustmentStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("mehmet");
        assertThat(approved.decidedAt()).isEqualTo(LATER);
        // İsteyen kaydı DEĞİŞMEZ: denetim izi iki kişiyi de tutar.
        assertThat(approved.adjustedBy()).isEqualTo("ayse");
    }

    @Test
    @DisplayName("zaten karara bağlanmış düzeltme İKİNCİ KEZ onaylanamaz")
    void cannotApproveTwice() {
        StockAdjustment approved = pending("ayse").approvedBy("mehmet", LATER);

        // Aksi hâlde stok iki kez değişirdi.
        assertThatThrownBy(() -> approved.approvedBy("zeynep", LATER))
                .isInstanceOf(AdjustmentNotPendingException.class);
    }

    @Test
    @DisplayName("anında uygulanmış düzeltme onaya AÇILAMAZ")
    void appliedCannotBeApproved() {
        StockAdjustment applied = StockAdjustment.applied("adj-2", "SKU-1", -5,
                AdjustmentReason.DAMAGED, null, "ayse", NOW);

        assertThatThrownBy(() -> applied.approvedBy("mehmet", LATER))
                .isInstanceOf(AdjustmentNotPendingException.class);
    }

    @Test
    @DisplayName("karar verilmiş kaydın karar vereni OLMAK ZORUNDA")
    void decidedRecordMustCarryItsDecider() {
        // Veritabanındaki CHECK kısıtının kod tarafındaki eşi. Denetim izinin değeri
        // tamlığında: "onaylandı ama kim onayladı bilinmiyor" bir iz değildir.
        assertThatThrownBy(() -> new StockAdjustment("adj-3", "SKU-1", -5,
                AdjustmentReason.DAMAGED, null, "ayse", NOW,
                AdjustmentStatus.APPROVED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("beklemede olan kaydın karar vereni OLMAMALI")
    void pendingRecordMustNotCarryADecider() {
        assertThatThrownBy(() -> new StockAdjustment("adj-4", "SKU-1", -5,
                AdjustmentReason.DAMAGED, null, "ayse", NOW,
                AdjustmentStatus.PENDING, "mehmet", LATER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stok yalnızca APPLIED ve APPROVED durumlarında hareket etmiştir")
    void onlyAppliedAndApprovedMovedStock() {
        // Metrik ölçümü buna bakıyor: bekleyen ya da reddedilen bir düzeltme
        // sayılmamalı, çünkü hiçbir stok hareketi olmadı.
        assertThat(AdjustmentStatus.APPLIED.movedStock()).isTrue();
        assertThat(AdjustmentStatus.APPROVED.movedStock()).isTrue();
        assertThat(AdjustmentStatus.PENDING.movedStock()).isFalse();
        assertThat(AdjustmentStatus.REJECTED.movedStock()).isFalse();
    }
}
