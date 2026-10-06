package com.imdemo.im.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "inventory_product")
@Getter
@Setter
@NoArgsConstructor
public class InventoryProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String name;

    @Column(name = "pack_text", length = 200)
    private String packText;

    @Column(name = "case_qty")
    private Double caseQty;

    @Column(name = "sleeve_qty")
    private Double sleeveQty;

    @Column(nullable = false, length = 5)
    private String unit = "шт";

    @Column(nullable = false)
    private boolean active = true;

    /** Дробные значения разрешены только для кг и л. */
    public boolean allowsDecimal() {
        return "кг".equals(unit) || "л".equals(unit);
    }
}