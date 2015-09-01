/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "gc/g1/g1CollectedHeap.inline.hpp"
#include "gc/g1/g1OopClosures.inline.hpp"
#include "gc/g1/g1ParScanThreadState.inline.hpp"
#include "gc/g1/g1StringDedup.hpp"
#include "gc/shared/taskqueue.inline.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/prefetch.inline.hpp"

G1ParScanThreadState::G1ParScanThreadState(G1CollectedHeap* g1h, uint worker_id, ReferenceProcessor* rp)
  : _g1h(g1h),
    _refs(g1h->task_queue(worker_id)),
    _dcq(&g1h->dirty_card_queue_set()),
    _ct_bs(g1h->g1_barrier_set()),
    _g1_rem(g1h->g1_rem_set()),
    _hash_seed(17), _worker_id(worker_id),
    _term_attempts(0),
    _tenuring_threshold(g1h->g1_policy()->tenuring_threshold()),
    _age_table(false), _scanner(g1h, rp),
    _strong_roots_time(0), _term_time(0) {
  _scanner.set_par_scan_thread_state(this);
  // we allocate G1YoungSurvRateNumRegions plus one entries, since
  // we "sacrifice" entry 0 to keep track of surviving bytes for
  // non-young regions (where the age is -1)
  // We also add a few elements at the beginning and at the end in
  // an attempt to eliminate cache contention
  uint real_length = 1 + _g1h->g1_policy()->young_cset_region_length();
  uint array_length = PADDING_ELEM_NUM +
                      real_length +
                      PADDING_ELEM_NUM;
  _surviving_young_words_base = NEW_C_HEAP_ARRAY(size_t, array_length, mtGC);
  if (_surviving_young_words_base == NULL)
    vm_exit_out_of_memory(array_length * sizeof(size_t), OOM_MALLOC_ERROR,
                          "Not enough space for young surv histo.");
  _surviving_young_words = _surviving_young_words_base + PADDING_ELEM_NUM;
  memset(_surviving_young_words, 0, (size_t) real_length * sizeof(size_t));

  _plab_allocator = G1PLABAllocator::create_allocator(_g1h->allocator());

  _dest[InCSetState::NotInCSet]    = InCSetState::NotInCSet;
  // The dest for Young is used when the objects are aged enough to
  // need to be moved to the next space.
  _dest[InCSetState::Young]        = InCSetState::Old;
  _dest[InCSetState::Old]          = InCSetState::Old;

  _start = os::elapsedTime();
}

G1ParScanThreadState::~G1ParScanThreadState() {
  _plab_allocator->retire_alloc_buffers();
  delete _plab_allocator;
  FREE_C_HEAP_ARRAY(size_t, _surviving_young_words_base);
}

void G1ParScanThreadState::print_termination_stats_hdr(outputStream* const st) {
  st->print_raw_cr("GC Termination Stats");
  st->print_raw_cr("     elapsed  --strong roots-- -------termination------- ------waste (KiB)------");
  st->print_raw_cr("thr     ms        ms      %        ms      %    attempts  total   alloc    undo");
  st->print_raw_cr("--- --------- --------- ------ --------- ------ -------- ------- ------- -------");
}

void G1ParScanThreadState::print_termination_stats(outputStream* const st) const {
  const double elapsed_ms = elapsed_time() * 1000.0;
  const double s_roots_ms = strong_roots_time() * 1000.0;
  const double term_ms    = term_time() * 1000.0;
  size_t alloc_buffer_waste = 0;
  size_t undo_waste = 0;
  _plab_allocator->waste(alloc_buffer_waste, undo_waste);
  st->print_cr("%3u %9.2f %9.2f %6.2f "
               "%9.2f %6.2f " SIZE_FORMAT_W(8) " "
               SIZE_FORMAT_W(7) " " SIZE_FORMAT_W(7) " " SIZE_FORMAT_W(7),
               _worker_id, elapsed_ms, s_roots_ms, s_roots_ms * 100 / elapsed_ms,
               term_ms, term_ms * 100 / elapsed_ms, term_attempts(),
               (alloc_buffer_waste + undo_waste) * HeapWordSize / K,
               alloc_buffer_waste * HeapWordSize / K,
               undo_waste * HeapWordSize / K);
}

#ifdef ASSERT
bool G1ParScanThreadState::verify_ref(narrowOop* ref) const {
  assert(ref != NULL, "invariant");
  assert(UseCompressedOops, "sanity");
  assert(!has_partial_array_mask(ref), err_msg("ref=" PTR_FORMAT, p2i(ref)));
  oop p = oopDesc::load_decode_heap_oop(ref);
  assert(_g1h->is_in_g1_reserved(p),
         err_msg("ref=" PTR_FORMAT " p=" PTR_FORMAT, p2i(ref), p2i(p)));
  return true;
}

bool G1ParScanThreadState::verify_ref(oop* ref) const {
  assert(ref != NULL, "invariant");
  if (has_partial_array_mask(ref)) {
    // Must be in the collection set--it's already been copied.
    oop p = clear_partial_array_mask(ref);
    assert(_g1h->obj_in_cs(p),
           err_msg("ref=" PTR_FORMAT " p=" PTR_FORMAT, p2i(ref), p2i(p)));
  } else {
    oop p = oopDesc::load_decode_heap_oop(ref);
    assert(_g1h->is_in_g1_reserved(p),
           err_msg("ref=" PTR_FORMAT " p=" PTR_FORMAT, p2i(ref), p2i(p)));
  }
  return true;
}

bool G1ParScanThreadState::verify_task(StarTask ref) const {
  if (ref.is_narrow()) {
    return verify_ref((narrowOop*) ref);
  } else {
    return verify_ref((oop*) ref);
  }
}
#endif // ASSERT

void G1ParScanThreadState::trim_queue() {
  StarTask ref;
  do {
    // Drain the overflow stack first, so other threads can steal.
    while (_refs->pop_overflow(ref)) {
      dispatch_reference(ref);
    }

    while (_refs->pop_local(ref)) {
      dispatch_reference(ref);
    }
  } while (!_refs->is_empty());
}

HeapWord* G1ParScanThreadState::allocate_in_next_plab(InCSetState const state,
                                                      InCSetState* dest,
                                                      size_t word_sz,
                                                      AllocationContext_t const context) {
  assert(state.is_in_cset_or_humongous(), err_msg("Unexpected state: " CSETSTATE_FORMAT, state.value()));
  assert(dest->is_in_cset_or_humongous(), err_msg("Unexpected dest: " CSETSTATE_FORMAT, dest->value()));

  // Right now we only have two types of regions (young / old) so
  // let's keep the logic here simple. We can generalize it when necessary.
  if (dest->is_young()) {
    HeapWord* const obj_ptr = _plab_allocator->allocate(InCSetState::Old,
                                                        word_sz,
                                                        context);
    if (obj_ptr == NULL) {
      return NULL;
    }
    // Make sure that we won't attempt to copy any other objects out
    // of a survivor region (given that apparently we cannot allocate
    // any new ones) to avoid coming into this slow path.
    _tenuring_threshold = 0;
    dest->set_old();
    return obj_ptr;
  } else {
    assert(dest->is_old(), err_msg("Unexpected dest: " CSETSTATE_FORMAT, dest->value()));
    // no other space to try.
    return NULL;
  }
}

InCSetState G1ParScanThreadState::next_state(InCSetState const state, markOop const m, uint& age) {
  if (state.is_young()) {
    age = !m->has_displaced_mark_helper() ? m->age()
                                          : m->displaced_mark_helper()->age();
    if (age < _tenuring_threshold) {
      return state;
    }
  }
  return dest(state);
}

oop G1ParScanThreadState::copy_to_survivor_space(InCSetState const state,
                                                 oop const old,
                                                 markOop const old_mark) {
  const size_t word_sz = old->size();
  HeapRegion* const from_region = _g1h->heap_region_containing_raw(old);
  // +1 to make the -1 indexes valid...
  const int young_index = from_region->young_index_in_cset()+1;
  assert( (from_region->is_young() && young_index >  0) ||
         (!from_region->is_young() && young_index == 0), "invariant" );
  const AllocationContext_t context = from_region->allocation_context();

  uint age = 0;
  InCSetState dest_state = next_state(state, old_mark, age);
  HeapWord* obj_ptr = _plab_allocator->plab_allocate(dest_state, word_sz, context);

  // PLAB allocations should succeed most of the time, so we'll
  // normally check against NULL once and that's it.
  if (obj_ptr == NULL) {
    obj_ptr = _plab_allocator->allocate_direct_or_new_plab(dest_state, word_sz, context);
    if (obj_ptr == NULL) {
      obj_ptr = allocate_in_next_plab(state, &dest_state, word_sz, context);
      if (obj_ptr == NULL) {
        // This will either forward-to-self, or detect that someone else has
        // installed a forwarding pointer.
        return handle_evacuation_failure_par(old, old_mark);
      }
    }
  }

  assert(obj_ptr != NULL, "when we get here, allocation should have succeeded");
  assert(_g1h->is_in_reserved(obj_ptr), "Allocated memory should be in the heap");

#ifndef PRODUCT
  // Should this evacuation fail?
  if (_g1h->evacuation_should_fail()) {
    // Doing this after all the allocation attempts also tests the
    // undo_allocation() method too.
    _plab_allocator->undo_allocation(dest_state, obj_ptr, word_sz, context);
    return handle_evacuation_failure_par(old, old_mark);
  }
#endif // !PRODUCT

  // We're going to allocate linearly, so might as well prefetch ahead.
  Prefetch::write(obj_ptr, PrefetchCopyIntervalInBytes);

  const oop obj = oop(obj_ptr);
  const oop forward_ptr = old->forward_to_atomic(obj);
  if (forward_ptr == NULL) {
    Copy::aligned_disjoint_words((HeapWord*) old, obj_ptr, word_sz);

    if (dest_state.is_young()) {
      if (age < markOopDesc::max_age) {
        age++;
      }
      if (old_mark->has_displaced_mark_helper()) {
        // In this case, we have to install the mark word first,
        // otherwise obj looks to be forwarded (the old mark word,
        // which contains the forward pointer, was copied)
        obj->set_mark(old_mark);
        markOop new_mark = old_mark->displaced_mark_helper()->set_age(age);
        old_mark->set_displaced_mark_helper(new_mark);
      } else {
        obj->set_mark(old_mark->set_age(age));
      }
      age_table()->add(age, word_sz);
    } else {
      obj->set_mark(old_mark);
    }

    if (G1StringDedup::is_enabled()) {
      const bool is_from_young = state.is_young();
      const bool is_to_young = dest_state.is_young();
      assert(is_from_young == _g1h->heap_region_containing_raw(old)->is_young(),
             "sanity");
      assert(is_to_young == _g1h->heap_region_containing_raw(obj)->is_young(),
             "sanity");
      G1StringDedup::enqueue_from_evacuation(is_from_young,
                                             is_to_young,
                                             _worker_id,
                                             obj);
    }

    size_t* const surv_young_words = surviving_young_words();
    surv_young_words[young_index] += word_sz;

    if (obj->is_objArray() && arrayOop(obj)->length() >= ParGCArrayScanChunk) {
      // We keep track of the next start index in the length field of
      // the to-space object. The actual length can be found in the
      // length field of the from-space object.
      arrayOop(obj)->set_length(0);
      oop* old_p = set_partial_array_mask(old);
      push_on_queue(old_p);
    } else {
      HeapRegion* const to_region = _g1h->heap_region_containing_raw(obj_ptr);
      _scanner.set_region(to_region);
      obj->oop_iterate_backwards(&_scanner);
    }
    return obj;
  } else {
    _plab_allocator->undo_allocation(dest_state, obj_ptr, word_sz, context);
    return forward_ptr;
  }
}

oop G1ParScanThreadState::handle_evacuation_failure_par(oop old, markOop m) {
  assert(_g1h->obj_in_cs(old),
         err_msg("Object " PTR_FORMAT " should be in the CSet", p2i(old)));

  oop forward_ptr = old->forward_to_atomic(old);
  if (forward_ptr == NULL) {
    // Forward-to-self succeeded. We are the "owner" of the object.
    HeapRegion* r = _g1h->heap_region_containing(old);

    if (!r->evacuation_failed()) {
      r->set_evacuation_failed(true);
     _g1h->hr_printer()->evac_failure(r);
    }

    _g1h->preserve_mark_during_evac_failure(_worker_id, old, m);

    _scanner.set_region(r);
    old->oop_iterate_backwards(&_scanner);

    return old;
  } else {
    // Forward-to-self failed. Either someone else managed to allocate
    // space for this object (old != forward_ptr) or they beat us in
    // self-forwarding it (old == forward_ptr).
    assert(old == forward_ptr || !_g1h->obj_in_cs(forward_ptr),
           err_msg("Object " PTR_FORMAT " forwarded to: " PTR_FORMAT " "
                   "should not be in the CSet",
                   p2i(old), p2i(forward_ptr)));
    return forward_ptr;
  }
}
