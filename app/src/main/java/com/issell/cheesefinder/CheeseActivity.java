package com.issell.cheesefinder;

/*
 * Copyright (c) 2016 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */


import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Cancellable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;

public class CheeseActivity extends BaseSearchActivity {

    // Observable 의 라이프 사이클 관리
    private Disposable mDisposable;


    ///////////////////////////////////////////////////////////////////////////
    // Observable 준비
    /**
     * String 을 전달하는 Observable 을 반환
     * @return Observable\<String\>
     */
    // 1
    private Observable<String> createButtonClickObservable() {

        // 2 Observable 객체를 생성하여 이를 return 한다.
        // + Observable 객체는 create() 통해 생성한다.
        return Observable.create(new ObservableOnSubscribe<String>() {

            // 3 ObservableOnSubscribe 의 subscribe()
            @Override
            public void subscribe(final ObservableEmitter<String> emitter) throws Exception {
                // 4 '검색' 버튼에 OnClickListener 붙이기
                mSearchButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        // 5 해당 버튼이 클릭되면, emitter 의 onNext() 호출
                        //  onNext() 는 Observable 이 새로운 아이템 (인자 value) 을 Observer 에게 전달하는 메서드.
                        // 여기서 새로운 아이템은 String (검색창에 입력된 텍스트)
                        emitter.onNext(mQueryEditText.getText().toString());
                    }
                });

                // 6 emitter 의 setCancellable() 구현
                // 이때, 인자로 전달되는 Cancellable 익명 객체의 cancel() 에 구독을 취소할 경우 할 일을 작성
                emitter.setCancellable(new Cancellable() {
                    @Override
                    public void cancel() throws Exception {
                        // 7 리스너 해제, 메모리 릭 방지
                        mSearchButton.setOnClickListener(null);
                    }
                });
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////
    // Observable 준비 (검색창의 텍스트가 바뀔 때마다 아이템 emit)
    //1
    private Observable<String> createTextChangeObservable() {
        //2
        Observable<String> o = Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(final ObservableEmitter<String> emitter) throws Exception {
                //3
                final TextWatcher watcher = new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void afterTextChanged(Editable s) {}

                    //4
                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        emitter.onNext(s.toString());
                    }
                };

                //5
                mQueryEditText.addTextChangedListener(watcher);

                //6
                emitter.setCancellable(new Cancellable() {
                    @Override
                    public void cancel() throws Exception {
                        mQueryEditText.removeTextChangedListener(watcher);
                    }
                });
            }

        });

        return o
                // 아이템(String)의 길이가 2글자 이상인 경우만 emit
                .filter(new Predicate<String>() {
                    @Override
                    public boolean test(String query) throws Exception {
                        return query.length() >= 2;
                    }
                })

                // 시간차 적용 (타이핑을 계속하다가 멈추었을 때 적용, 1초)
                .debounce(1000, TimeUnit.MILLISECONDS);

    }


    ///////////////////////////////////////////////////////////////////////////
    // 액티비티의 start()
    @Override
    protected void onStart() {
        super.onStart();
        // 선택1. '검색' 버튼을 눌렀을 때 결과 띄우기
        //Observable<String> searchTextObservable = createButtonClickObservable();

        // 선택2. 검색창에 텍스트를 입력할 때마다 결과 띄우기
        //Observable<String> searchTextObservable = createTextChangeObservable();

        // 선택3. 선택1 + 선택2 를 모두 적용하기
        Observable<String> buttonClickStream = createButtonClickObservable();
        Observable<String> textChangeStream = createTextChangeObservable();
        Observable<String> searchTextObservable = Observable.merge(textChangeStream, buttonClickStream);

        /////////////////////////////////////////////////////////////////////////////////////

        mDisposable = searchTextObservable
                .observeOn(AndroidSchedulers.mainThread())
                // Observable 에서 아이템이 전달될 때마다 수행
                // 여기서는 프로그레스 바 보이기
                .doOnNext(new Consumer<String>() {
                    @Override
                    public void accept(String s) throws Exception {
                        showProgressBar();
                    }
                })
                // 다음 Operator 가 io 쓰레드에서 호출되도록 지정
                .observeOn(Schedulers.io())
                // 모든 아이템에 대한 검색 결과 리턴 (여기서는 아이템은 1회 1개뿐)
                .map(new Function<String, List<String>>() {
                    @Override
                    public List<String> apply(String s) throws Exception {
                        return mCheeseSearchEngine.search(s);
                    }
                })

                // 다음 Operator 는 main thread 에서 실행되도록 지정
                .observeOn(AndroidSchedulers.mainThread())

                // subscribe() 정의
                // Consumer<String> : emitter 를 통해 전달받은 값을 어떻게 소비할 것인가?
                .subscribe(new Consumer<List<String>>() {
                    //3 받아온 String 값을 어떻게 받아들일 것인가?
                    @Override
                    public void accept(List<String> result) throws Exception {
                        // 4 검색 수행 후 결과를 보여준다.
                        // 여기서 showResult() 는 BaseSearchActivity
                        hideProgressBar();
                        showResult(result);
                    }
                });


    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mDisposable.isDisposed()) {
            mDisposable.dispose();
        }
    }
}
