"""eval_runner.py 순수 함수 단위 테스트 (API 호출 없음)."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))


def cosine_similarity(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(x * x for x in b) ** 0.5
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return round(dot / (norm_a * norm_b), 4)


def test_identical_vectors_give_one():
    v = [1.0, 0.5, 0.3]
    assert cosine_similarity(v, v) == 1.0


def test_orthogonal_vectors_give_zero():
    assert cosine_similarity([1, 0], [0, 1]) == 0.0


def test_zero_vector_returns_zero():
    assert cosine_similarity([0, 0], [1, 2]) == 0.0


def test_keyword_accuracy_empty_keywords():
    from eval_runner import keyword_accuracy
    assert keyword_accuracy("anything", []) == 0.0


def test_keyword_accuracy_match():
    from eval_runner import keyword_accuracy
    assert keyword_accuracy("로그인 이메일 테스트", ["로그인", "이메일"]) == 1.0


def test_keyword_accuracy_partial():
    from eval_runner import keyword_accuracy
    assert keyword_accuracy("로그인만 있음", ["로그인", "이메일"]) == 0.5
