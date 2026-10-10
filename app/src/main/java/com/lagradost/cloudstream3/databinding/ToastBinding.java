package com.lagradost.cloudstream3.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.viewbinding.ViewBinding;

import com.hikari.app.R;

/**
 * Replacement for the ViewBinding class the prebuilt cloudstream3.jar ships.
 *
 * <p>Why this exists: the jar's own {@code ToastBinding} is a generated
 * ViewBinding class, so it implements {@link androidx.viewbinding.ViewBinding},
 * calls {@code androidx.viewbinding.ViewBindings.findChildViewById(...)} and —
 * crucially — inflates by the resource id baked into the jar's own
 * {@code com.lagradost.cloudstream3.R$layout} when the jar was compiled.
 *
 * <p>Neither of those survives a repackaged host:
 * <ul>
 *   <li>Hikari does not ship the androidx.viewbinding runtime (it enables
 *       Compose, not viewBinding), so the jar's class cannot be LINKED. ART
 *       reports that as
 *       {@code NoClassDefFoundError: Failed resolution of:
 *       Lcom/lagradost/cloudstream3/databinding/ToastBinding;}
 *       with {@code Caused by: ClassNotFoundException: ...ToastBinding} — the
 *       class LOOKS absent even though it is right there in the dex.</li>
 *   <li>Even when linked, {@code R.layout.<baked id>} points at a slot in
 *       CloudStream's own resource table, which Hikari's resources do not
 *       share, so inflating would throw Resources.NotFoundException.</li>
 * </ul>
 *
 * <p>{@code CommonActivity.showToast} uses exactly three members — the static
 * {@code inflate(LayoutInflater)}, the {@code text} field and
 * {@code getRoot()} — so this class provides those (plus the usual binding
 * members) against a Hikari-owned layout. It is the ONLY class in the whole jar
 * that references ToastBinding, so this is the entire surface.
 *
 * <p>The jar's copy is dropped in {@code cloudstreamJarClean} (see
 * app/build.gradle.kts), exactly like the WebViewResolver/CloudStreamApp
 * shadows, so there is no duplicate class.
 */
public final class ToastBinding implements ViewBinding {

    /** The toast's card. {@code getRoot()} is declared to return this type
     *  because that is the descriptor CommonActivity's call site expects. */
    public final CardView root;

    /** The message view. A public field (not just a getter) because the
     *  generated binding CommonActivity was compiled against exposed one. */
    public final TextView text;

    private ToastBinding(CardView root, TextView text) {
        this.root = root;
        this.text = text;
    }

    @Override
    public CardView getRoot() {
        return root;
    }

    public TextView getText() {
        return text;
    }

    public static ToastBinding inflate(LayoutInflater inflater) {
        return inflate(inflater, null, false);
    }

    public static ToastBinding inflate(LayoutInflater inflater, ViewGroup parent) {
        return inflate(inflater, parent, false);
    }

    public static ToastBinding inflate(LayoutInflater inflater, ViewGroup parent, boolean attachToParent) {
        View view = inflater.inflate(R.layout.hikari_toast, parent, false);
        if (attachToParent && parent != null) parent.addView(view);
        return bind(view);
    }

    public static ToastBinding bind(View view) {
        CardView root = (CardView) view;
        TextView text = view.findViewById(R.id.toast_text);
        return new ToastBinding(root, text);
    }
}
