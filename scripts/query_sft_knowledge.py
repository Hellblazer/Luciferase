#!/usr/bin/env python3
"""
Query tool for Space-Filling Trees Motion Planning knowledge base.
Provides convenient access to indexed SFT concepts and k-NN optimizations.
"""

import sys
from chroma_sft_motion_planning_indexer import SFTMotionPlanningIndexer


def print_result(result: dict, index: int = 1):
    """Pretty print a single query result."""
    print(f"\n{index}. {result['metadata'].get('title', 'Untitled')}")
    print(f"   ID: {result['id']}")
    print(f"   Category: {result['metadata'].get('category', 'N/A')}")
    print(f"   Relevance: {result['metadata'].get('relevance', 'N/A')}")
    print(f"   Distance: {result['distance']:.4f}")
    print(f"   Content (first 300 chars):")
    content = result['content'][:300] + "..." if len(result['content']) > 300 else result['content']
    for line in content.split('\n'):
        print(f"      {line}")


def query_by_topic(indexer: SFTMotionPlanningIndexer, topic: str, n_results: int = 3):
    """Query knowledge base by topic."""
    print(f"\n{'='*70}")
    print(f"Topic: {topic}")
    print(f"{'='*70}")

    results = indexer.query_collection(topic, n_results=n_results)

    if not results:
        print("No results found.")
        return

    print(f"Found {len(results)} relevant documents:\n")
    for idx, result in enumerate(results, 1):
        print_result(result, idx)


def list_all_documents(indexer: SFTMotionPlanningIndexer):
    """List all indexed documents with their IDs."""
    print("\n" + "="*70)
    print("INDEXED DOCUMENTS REFERENCE")
    print("="*70)

    categories = {
        "SFC Concepts": [
            "sft-01-overview",
            "sft-02-vs-rrt",
            "sft-03-incremental-search",
            "sft-04-tree-traversal",
            "sft-05-nn-queries",
            "sft-06-knn-optimization-lucien",
            "sft-07-performance",
            "sft-08-collision-detection",
        ],
        "k-NN Optimization": [
            "knn-01-bottleneck",
            "knn-02-sfc-locality",
            "knn-03-incremental-knn",
            "knn-04-multidimensional-knn",
            "knn-05-radius-search",
            "knn-06-concurrent-knn",
            "knn-07-metric-space",
        ],
        "Lucien Integration": [
            "lucien-01-current-knn",
            "lucien-02-sfc-pruning",
            "lucien-03-morton-knn-cache",
            "lucien-04-tetree-sfc-ordering",
            "lucien-05-collision-via-knn",
            "lucien-06-performance-targets",
        ],
    }

    for category, doc_ids in categories.items():
        print(f"\n{category}:")
        for doc_id in doc_ids:
            print(f"  - {doc_id}")


def main():
    """Main interactive query tool."""
    indexer = SFTMotionPlanningIndexer()

    print("\n" + "="*70)
    print("Space-Filling Trees Motion Planning Knowledge Base")
    print("="*70)
    print("\nQuery the indexed knowledge about SFC optimization for motion planning")
    print("and k-NN search acceleration in spatial indices.\n")

    if len(sys.argv) > 1:
        # Command-line query
        query = " ".join(sys.argv[1:])
        results = indexer.query_collection(query, n_results=5)
        print(f"\nQuery: '{query}'")
        print(f"Found {len(results)} results:\n")
        for idx, result in enumerate(results, 1):
            print_result(result, idx)
    else:
        # Interactive mode
        print("Interactive Mode - Enter queries to search the knowledge base.")
        print("Commands:")
        print("  'list'  - List all indexed documents")
        print("  'demo'  - Run example queries")
        print("  'quit'  - Exit\n")

        while True:
            query = input("Enter query (or command): ").strip()

            if not query:
                continue
            elif query.lower() == 'quit':
                print("Goodbye!")
                break
            elif query.lower() == 'list':
                list_all_documents(indexer)
            elif query.lower() == 'demo':
                demo_queries(indexer)
            else:
                results = indexer.query_collection(query, n_results=5)
                print(f"\nFound {len(results)} results:\n")
                for idx, result in enumerate(results, 1):
                    print_result(result, idx)


def demo_queries(indexer: SFTMotionPlanningIndexer):
    """Run example queries to demonstrate knowledge base."""
    demo_topics = [
        "How can space-filling curves optimize k-nearest neighbor search?",
        "What is the bottleneck in motion planning with RRT?",
        "How to implement k-NN caching in spatial indices?",
        "Performance targets for optimized k-NN search",
        "Collision detection acceleration with SFC ordering",
    ]

    for topic in demo_topics:
        query_by_topic(indexer, topic, n_results=2)

    print("\n" + "="*70)
    print("Demo Complete - Use free-form queries for custom searches")
    print("="*70)


if __name__ == "__main__":
    main()
