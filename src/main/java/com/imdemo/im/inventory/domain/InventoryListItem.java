package com.imdemo.im.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "inventory_list_item")
@Getter
@Setter
@NoArgsConstructor
public class InventoryListItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id")
    private InventoryList list;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private InventoryProduct product;

    @Column(nullable = false, length = 150)
    private String zone;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}