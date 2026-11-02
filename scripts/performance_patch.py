"""Version-pinned performance edits; called only on the new build copy."""
import re

HOOK = 'Lcn/kuwo/bridge/PerformanceHooks;'
BASE = 'cn/kuwo/kwmusiccar/ui/base/'
HOME = 'cn/kuwo/kwmusiccar/ui/homerecommend/'


def apply_performance(file, change, inject, edits):
    def modify(relative, signature, transform):
        p = file(relative)
        text = p.read_text()
        m = re.search(r'(?m)^\.method [^\n]* '+re.escape(signature)+r'\n.*?^\.end method', text, re.S)
        assert m, (relative, signature)
        p.write_text(text[:m.start()]+transform(m.group())+text[m.end():])
        edits.append({'class': relative, 'method': signature, 'kind': 'performance'})

    def append(relative, source):
        p = file(relative)
        p.write_text(p.read_text()+'\n\n'+source.strip()+'\n')

    def wrap(relative, signature, name, source, strip_super=None):
        def rename(method):
            header, body = method.split('\n', 1)
            header = re.sub(r'\b(private|protected)\b', 'public', header)
            header = header.replace(signature, name+signature[signature.index('('):])
            if strip_super:
                assert body.count(strip_super) == 1, (relative, strip_super)
                body = body.replace(strip_super, '')
            return header+'\n'+body
        modify(relative, signature, rename)
        append(relative, source)

    def after(relative, signature, instruction, code):
        def apply(method):
            assert method.count(instruction) == 1, (relative, signature, instruction)
            return method.replace(instruction, instruction+'\n\n'+code)
        modify(relative, signature, apply)

    wrap('s5/b.smali', 'e(Landroid/app/Application;)V', 'leanInitNow', f'''
.method public final e(Landroid/app/Application;)V
    .locals 0
    invoke-static {{p0, p1}}, {HOOK}->deferOptional(Ljava/lang/Object;Landroid/app/Application;)V
    return-void
.end method''')
    inject('cn/kuwo/kwmusiccar/ui/MainFragment.smali', 'onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V',
           f'    invoke-static {{p0}}, {HOOK}->homeCreated(Ljava/lang/Object;)V', True)
    append('cn/kuwo/kwmusiccar/ui/MainActivity.smali', f'''
.method public onUserInteraction()V
    .locals 0
    invoke-super {{p0}}, Lcn/kuwo/kwmusiccar/ui/BaseActivity;->onUserInteraction()V
    invoke-static {{}}, {HOOK}->interaction()V
    return-void
.end method''')

    # Keep inflation and framework view-state restoration; postpone adapter and observer work.
    for fragment in ['cn/kuwo/kwmusiccar/ui/musiclib/MusicLibFragment.smali', HOME+'HomeRecommendFragment.smali']:
        super_call = '    invoke-super {p0, p1, p2}, Lcn/kuwo/kwmusiccar/ui/base/LazyLoadFragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V'
        wrap(fragment, 'onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V', 'leanBindView', f'''
.method public onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V
    .locals 0
{super_call}
    invoke-static {{p0, p1, p2}}, {HOOK}->bindWhenVisible(Ljava/lang/Object;Landroid/view/View;Landroid/os/Bundle;)V
    return-void
.end method''', strip_super=super_call)
    inject(BASE+'LazyLoadFragment.smali', 'S4(Landroid/os/Bundle;)V', f'    invoke-static {{p0}}, {HOOK}->ensureBound(Ljava/lang/Object;)V')
    after(BASE+'LazyLoadFragment.smali', 'setUserVisibleHint(Z)V',
          '    invoke-super {p0, p1}, Landroidx/fragment/app/Fragment;->setUserVisibleHint(Z)V',
          f'    invoke-static {{p0, p1}}, {HOOK}->pageVisibility(Ljava/lang/Object;Z)V')
    inject(BASE+'BaseKuwoFragment.smali', 'onDestroyView()V', f'    invoke-static {{p0}}, {HOOK}->releasePage(Ljava/lang/Object;)V')

    wrap('v8/n0.smali', 'A(Ljava/lang/String;)V', 'leanSuggestNow', f'''
.method public A(Ljava/lang/String;)V
    .locals 0
    invoke-static {{p0, p1}}, {HOOK}->suggest(Ljava/lang/Object;Ljava/lang/String;)V
    return-void
.end method''')
    append('v8/b0.smali', '''
.method public leanForgetTask(Lx8/a;)V
    .locals 1
    iget-object v0, p0, Lv8/b0;->c:Ljava/util/List;
    invoke-interface {v0, p1}, Ljava/util/List;->remove(Ljava/lang/Object;)Z
    return-void
.end method''')
    append('v8/n0.smali', '''
.method public leanCancelSuggestion()V
    .locals 1
    iget-object v0, p0, Lv8/n0;->e:Lx8/a;
    if-eqz v0, :lean_cancel_done
    invoke-interface {v0}, Lx8/a;->cancel()V
    invoke-virtual {p0, v0}, Lv8/b0;->leanForgetTask(Lx8/a;)V
    const/4 v0, 0x0
    iput-object v0, p0, Lv8/n0;->e:Lx8/a;
    :lean_cancel_done
    return-void
.end method

.method public leanClearSuggestions()V
    .locals 1
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    invoke-direct {p0, v0}, Lv8/n0;->B(Ljava/util/List;)V
    return-void
.end method''')
    inject('v8/n0.smali', 'l()V', f'    invoke-static {{p0}}, {HOOK}->cancelSuggestions(Ljava/lang/Object;)V')
    inject('v8/n0$b.smali', '<init>(Lv8/n0;)V', f'    invoke-static {{p0, p1}}, {HOOK}->registerSuggestion(Ljava/lang/Object;Ljava/lang/Object;)V', True)
    inject('v8/n0$b.smali', 'd(Lm/c;)V', f'''    invoke-static {{p0}}, {HOOK}->acceptCallback(Ljava/lang/Object;)Z
    move-result v0
    if-nez v0, :lean_suggest_current
    return-void
    :lean_suggest_current''')
    inject('cn/kuwo/kwmusiccar/ui/fragment/SearchFragment.smali', 'x5(Ljava/lang/String;Lcn/kuwo/base/log/SearchFrom;)Z', f'''    iget-object v0, p0, Lcn/kuwo/kwmusiccar/ui/base/BaseMvpFragment;->D:Lv8/o;
    invoke-static {{v0}}, {HOOK}->cancelSuggestions(Ljava/lang/Object;)V''')

    wrap(HOME+'h.smali', 'M(Z)V', 'leanRefreshNow', f'''
.method public M(Z)V
    .locals 0
    invoke-static {{p0, p1}}, {HOOK}->refreshHome(Ljava/lang/Object;Z)V
    return-void
.end method''')
    wrap(HOME+'h.smali', 'J(Lm/c;)Lse/h;', 'leanApplyHomeResponse', f'''
.method private J(Lm/c;)Lse/h;
    .locals 1
    invoke-static {{p0, p1}}, {HOOK}->homeResponse(Ljava/lang/Object;Ljava/lang/Object;)V
    const/4 v0, 0x0
    return-object v0
.end method''')
    inject(HOME+'d.smali', '<init>(Lcn/kuwo/kwmusiccar/ui/homerecommend/h;)V',
           f'    invoke-static {{p0, p1}}, {HOOK}->registerHome(Ljava/lang/Object;Ljava/lang/Object;)V', True)
    inject(HOME+'d.smali', 'invoke(Ljava/lang/Object;)Ljava/lang/Object;', f'''    invoke-static {{p0}}, {HOOK}->acceptCallback(Ljava/lang/Object;)Z
    move-result v0
    if-nez v0, :lean_home_current
    const/4 v0, 0x0
    return-object v0
    :lean_home_current''')
    inject(HOME+'HomeRecommendFragment.smali', 'k4()V', f'    invoke-static {{p0}}, {HOOK}->hideRecommendation(Ljava/lang/Object;)V', True)
    inject(HOME+'HomeRecommendFragment.smali', 'z4()V', f'    invoke-static {{p0}}, {HOOK}->showRecommendation(Ljava/lang/Object;)V')
    change(HOME+'HomeRecommendFragment.smali', 'onResume()V', f'''    invoke-super {{p0}}, Lcn/kuwo/kwmusiccar/ui/base/BaseKuwoFragment;->onResume()V
    invoke-static {{p0}}, {HOOK}->showRecommendation(Ljava/lang/Object;)V
    return-void''', 0)

    for signature in ['C0(Ljava/util/List;II)V', 'w(ILcn/kuwo/base/config/ContinuePlayFrom;)V', 'h0(Lcn/kuwo/base/bean/Music;)V']:
        inject('q5/r0.smali', signature, f'    invoke-static {{}}, {HOOK}->playbackCommand()V')
    after('cn/kuwo/kwmusiccar/ui/nowplaying/NowPlayingFragment.smali', 'c7(Ljava/lang/String;Z)V',
          '    iput-object p1, p0, Lcn/kuwo/kwmusiccar/ui/nowplaying/NowPlayingFragment;->s0:Ljava/lang/String;',
          f'    invoke-static {{p0}}, {HOOK}->boundCover(Ljava/lang/Object;)V')
    change(BASE+'BaseKuwoFragment.smali', 'N3(Landroidx/recyclerview/widget/RecyclerView;)V', '''    if-eqz p1, :lean_animator_done
    const/4 v0, 0x0
    invoke-virtual {p1, v0}, Landroidx/recyclerview/widget/RecyclerView;->setItemAnimator(Landroidx/recyclerview/widget/RecyclerView$ItemAnimator;)V
    :lean_animator_done
    return-void''', 1)
