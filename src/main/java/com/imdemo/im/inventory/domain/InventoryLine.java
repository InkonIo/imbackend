package com.imdemo.im.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "inventory_line")
@Getter
@Setter
@NoArgsConstructor
public class InventoryLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "count_id")
    private InventoryCount inventory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private InventoryProduct product;

    @Column(nullable = false, length = 150)
    private String zone;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    private Double cs;
    private Double slv;
    private Double ea;

    @Column(name = "none_flag", nullable = false)
    private boolean noneFlag;

    private Double total;

    @Column(name = "prev_cs")
    private Double prevCs;

    @Column(name = "prev_slv")
    private Double prevSlv;

    @Column(name = "prev_ea")
    private Double prevEa;

    @Column(name = "prev_total")
    private Double prevTotal;

    @Column(name = "prev_date")
    private LocalDate prevDate;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public boolean isFilled() {
        return noneFlag || cs != null || slv != null || ea != null;
    }
}