package com.imdemo.im.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "inventory_count")
@Getter
@Setter
@NoArgsConstructor
public class InventoryCount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id")
    private InventoryList list;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "count_date", nullable = false)
    private LocalDate countDate;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "shift_id")
    private Long shiftId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CountStatus status = CountStatus.DRAFT;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @OneToMany(mappedBy = "inventory", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<InventoryLine> lines = new ArrayList<>();
}