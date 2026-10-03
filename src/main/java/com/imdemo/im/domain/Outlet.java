package com.imdemo.im.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "outlet")
@Getter
@Setter
@NoArgsConstructor
public class Outlet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id")
    private City city;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(nullable = false)
    private boolean active = true;
}