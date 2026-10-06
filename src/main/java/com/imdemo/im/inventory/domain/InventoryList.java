package com.imdemo.im.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "inventory_list")
@Getter
@Setter
@NoArgsConstructor
public class InventoryList {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String title;

    /** Дни недели через запятую: 1 = пн … 7 = вс. */
    @Column(nullable = false, length = 20)
    private String weekdays;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "list")
    @OrderBy("sortOrder ASC")
    private List<InventoryListItem> items = new ArrayList<>();

    public boolean runsOn(int isoDayOfWeek) {
        return Arrays.stream(weekdays.split(",")).map(String::trim)
                .anyMatch(d -> d.equals(String.valueOf(isoDayOfWeek)));
    }
}