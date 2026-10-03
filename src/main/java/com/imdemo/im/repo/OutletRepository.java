package com.imdemo.im.repo;

import com.imdemo.im.domain.Outlet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutletRepository extends JpaRepository<Outlet, Long> {
    Optional<Outlet> findByCityNameAndName(String cityName, String name);
}