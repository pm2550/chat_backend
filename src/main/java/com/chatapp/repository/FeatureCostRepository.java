package com.chatapp.repository;

import com.chatapp.entity.FeatureCost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FeatureCostRepository extends JpaRepository<FeatureCost, String> {
    Optional<FeatureCost> findByFeatureKey(String featureKey);
}
