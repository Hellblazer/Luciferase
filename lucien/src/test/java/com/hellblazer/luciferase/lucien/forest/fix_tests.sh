#!/bin/bash

# Fix method calls in test files

# Fix getTrees() -> getAllTrees()
find . -name "*.java" -exec sed -i '' 's/forest\.getTrees()/forest.getAllTrees()/g' {} \;

# Fix findKNearestNeighbors without maxDistance parameter
find . -name "*.java" -exec sed -i '' 's/queries\.findKNearestNeighbors(\([^,]*\), \([^)]*\))/queries.findKNearestNeighbors(\1, \2, Float.MAX_VALUE)/g' {} \;

# Fix singleTree.findKNearestNeighbors -> kNearestNeighbors
find . -name "*.java" -exec sed -i '' 's/singleTree\.findKNearestNeighbors/singleTree.kNearestNeighbors/g' {} \;

# Fix updatePosition -> updateEntity
find . -name "*.java" -exec sed -i '' 's/singleTree\.updatePosition/singleTree.updateEntity/g' {} \;
find . -name "*.java" -exec sed -i '' 's/singleTree\.updateEntityPosition/singleTree.updateEntity/g' {} \;

# Fix findNeighborsWithinDistance -> kNearestNeighbors with large k
find . -name "*.java" -exec sed -i '' 's/singleTree\.findNeighborsWithinDistance(\([^,]*\), \([^)]*\))/singleTree.kNearestNeighbors(\1, Integer.MAX_VALUE, \2)/g' {} \;
find . -name "*.java" -exec sed -i '' 's/singleTree\.entitiesWithinDistance(\([^,]*\), \([^)]*\))/singleTree.kNearestNeighbors(\1, Integer.MAX_VALUE, \2)/g' {} \;

# Fix forest.insert -> entityManager.insert
find . -name "*.java" -exec sed -i '' 's/forest\.insert(/entityManager.insert(/g' {} \;
find . -name "*.java" -exec sed -i '' 's/forest\.remove(/entityManager.remove(/g' {} \;
find . -name "*.java" -exec sed -i '' 's/forest\.updatePosition(/entityManager.updatePosition(/g' {} \;
find . -name "*.java" -exec sed -i '' 's/forest\.getEntityPosition(/entityManager.getEntityPosition(/g' {} \;